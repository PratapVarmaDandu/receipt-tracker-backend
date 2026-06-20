package com.receipttracker.immigration.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.receipttracker.config.StoragePathResolver;
import com.receipttracker.immigration.dto.GeneratedPdfPacketDTO;
import com.receipttracker.immigration.model.*;
import com.receipttracker.immigration.model.question.CanonicalQuestion;
import com.receipttracker.immigration.model.question.FormFieldEntry;
import com.receipttracker.immigration.model.question.FormFieldMapping;
import com.receipttracker.immigration.model.question.FormSectionMapping;
import com.receipttracker.immigration.repository.*;
import com.receipttracker.model.User;
import com.receipttracker.repository.UserRepository;
import com.receipttracker.service.EncryptionService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class ImmPdfGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ImmPdfGenerationService.class);

    @Autowired private FilingPackageRepository packageRepo;
    @Autowired private FilingPackageAnswerRepository answerRepo;
    @Autowired private FormVersionRepository formVersionRepo;
    @Autowired private FormVersionAuditEventRepository formVersionAuditRepo;
    @Autowired private GeneratedPdfPacketRepository packetRepo;
    @Autowired private ImmigrationCaseRepository caseRepo;
    @Autowired private ChecklistItemRepository checklistItemRepo;
    @Autowired private CanonicalQuestionRegistry questionRegistry;
    @Autowired private EncryptionService encryptionService;
    @Autowired private AuditService auditService;
    @Autowired private PermissionService permissionService;
    @Autowired private StoragePathResolver storagePathResolver;
    @Autowired private UserRepository userRepo;
    @Autowired private ObjectMapper objectMapper;

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        OAuth2User principal = (OAuth2User) auth.getPrincipal();
        String googleId = principal.getAttribute("sub");
        return userRepo.findByGoogleId(googleId)
                .orElseThrow(() -> new RuntimeException("Authenticated user not found"));
    }

    // ── Generate ──────────────────────────────────────────────────────────────

    @Transactional
    public GeneratedPdfPacketDTO generatePacket(Long caseId, Long packageId, boolean overridePendingReview) {
        log.info(">>> generatePacket() caseId={} packageId={} override={}", caseId, packageId, overridePendingReview);
        User caller = currentUser();
        permissionService.requireAccess(caller, caseId, GrantScope.APPROVE_FORMS);

        FilingPackage pkg = packageRepo.findByIdAndCaseId(packageId, caseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Package not found"));
        ImmigrationCase immCase = caseRepo.findById(caseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found"));

        List<String> formTypes = parseList(pkg.getSelectedFormTypesJson());

        // ── Pre-generation checks ─────────────────────────────────────────────
        // Check 6: package must be APPROVED
        if (!"APPROVED".equals(pkg.getStatus())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Package must be APPROVED before generating PDF. Current status: " + pkg.getStatus());
        }

        Map<String, FormVersion> approvedVersions = new LinkedHashMap<>();
        List<String> preflightErrors = new ArrayList<>();

        for (String formType : formTypes) {
            // Check 1: APPROVED version exists
            FormVersion approved = formVersionRepo
                    .findFirstByFormTypeAndStatusOrderByCreatedAtDesc(formType, "APPROVED")
                    .orElse(null);
            if (approved == null) {
                preflightErrors.add("No APPROVED form version for " + formType);
                continue;
            }
            // Check 3: mapping verified
            if (!approved.isFieldMappingVerified()) {
                preflightErrors.add("Field mapping not verified for " + formType + " (edition " + approved.getEditionDate() + ")");
            }
            approvedVersions.put(formType, approved);

            // Check 2: no newer PENDING_REVIEW version
            if (!overridePendingReview) {
                boolean hasPending = formVersionRepo.findByFormTypeOrderByCreatedAtDesc(formType)
                        .stream()
                        .anyMatch(v -> "PENDING_REVIEW".equals(v.getStatus())
                                && v.getCreatedAt().isAfter(approved.getCreatedAt()));
                if (hasPending) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "PENDING_REVIEW_EXISTS: " + formType + " has a newer unreviewed version. "
                            + "Set overridePendingReview=true to generate with the current approved version.");
                }
            }
        }

        if (!preflightErrors.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Pre-generation checks failed: " + String.join("; ", preflightErrors));
        }

        // Check 4: required answers present
        List<CanonicalQuestion> questions = questionRegistry.getQuestionsForForms(formTypes);
        List<FilingPackageAnswer> answers = answerRepo.findByFilingPackageId(packageId);
        Map<String, FilingPackageAnswer> answerMap = answers.stream()
                .collect(Collectors.toMap(FilingPackageAnswer::getQuestionKey, a -> a, (a, b) -> a));

        List<String> missingAnswers = questions.stream()
                .filter(CanonicalQuestion::isRequired)
                .filter(q -> {
                    FilingPackageAnswer a = answerMap.get(q.getKey());
                    return a == null || a.getValueJson() == null || a.getValueJson().isBlank();
                })
                .map(CanonicalQuestion::getKey)
                .toList();
        if (!missingAnswers.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Missing required answers: " + String.join(", ", missingAnswers));
        }

        // Check 5: required checklist items UPLOADED or WAIVED
        List<String> blockedItems = checklistItemRepo
                .findByCaseIdOrderByCategoryAscSortOrderAsc(caseId)
                .stream()
                .filter(ChecklistItem::isRequired)
                .filter(i -> !"UPLOADED".equals(i.getStatus()) && !"WAIVED".equals(i.getStatus()))
                .map(ChecklistItem::getLabel)
                .toList();
        if (!blockedItems.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Required checklist items not complete: " + String.join(", ", blockedItems));
        }

        // ── PDF generation ────────────────────────────────────────────────────
        List<Map<String, Object>> formVersionsUsedRaw = new ArrayList<>();
        List<Map<String, Object>> auditEntries = new ArrayList<>();
        List<byte[]> formPdfs = new ArrayList<>();
        List<String> formNames = new ArrayList<>();

        for (String formType : formTypes) {
            FormVersion fv = approvedVersions.get(formType);
            if (fv == null) continue;

            formVersionsUsedRaw.add(Map.of(
                    "formType", formType,
                    "versionId", fv.getId(),
                    "editionDate", fv.getEditionDate()
            ));

            // Load PDF from disk
            if (fv.getPdfStorageKey() == null) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Form " + formType + " approved version has no PDF on disk (scheduler not yet run).");
            }
            Path pdfPath = storagePathResolver.asPath().resolve(fv.getPdfStorageKey());
            byte[] pdfBytes;
            try {
                pdfBytes = Files.readAllBytes(pdfPath);
            } catch (IOException e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Could not load PDF for " + formType + ": " + e.getMessage());
            }

            byte[] filledPdf = fillForm(formType, fv, pdfBytes, answerMap, auditEntries);
            formPdfs.add(filledPdf);
            formNames.add(formType + "_" + fv.getEditionDate().replace("/", "-") + ".pdf");

            // FormVersion audit: USED_IN_GENERATION
            FormVersionAuditEvent fvAudit = new FormVersionAuditEvent();
            fvAudit.setFormType(formType);
            fvAudit.setEditionDate(fv.getEditionDate());
            fvAudit.setAction("USED_IN_GENERATION");
            fvAudit.setPerformedByUserId(caller.getId());
            fvAudit.setDetail("{\"caseId\":" + caseId + ",\"packageId\":" + packageId + "}");
            formVersionAuditRepo.save(fvAudit);
        }

        // Cover sheet
        byte[] coverSheet = buildCoverSheet(immCase, formVersionsUsedRaw, caller);

        // ZIP
        String zipKey = "pdf-packets/" + caseId + "/" + UUID.randomUUID() + ".zip";
        Path zipPath = storagePathResolver.asPath().resolve(zipKey);
        try {
            Files.createDirectories(zipPath.getParent());
            buildZip(zipPath, coverSheet, formPdfs, formNames);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to write ZIP: " + e.getMessage());
        }

        // Save packet entity
        GeneratedPdfPacket packet = new GeneratedPdfPacket();
        packet.setPackageId(packageId);
        packet.setCaseId(caseId);
        packet.setFormVersionsUsedJson(toJson(formVersionsUsedRaw));
        packet.setGeneratedAt(LocalDateTime.now());
        packet.setGeneratedByUserId(caller.getId());
        packet.setStatus("DRAFT");
        packet.setPdfStorageKey(zipKey);
        packet.setGenerationAuditJson(toJson(auditEntries));
        packet = packetRepo.save(packet);

        // Update package status
        pkg.setStatus("GENERATED");
        packageRepo.save(pkg);

        // Case activity feed audit
        auditService.append(immCase, caller, "PDF_GENERATED",
                "{\"packetId\":" + packet.getId() + ",\"forms\":" + toJson(formTypes) + "}",
                FeedVisibility.ATTORNEY_ONLY);

        // Field-level audit: PDF packet generated
        auditService.appendPdfGeneration(packet.getId(), caseId, formVersionsUsedRaw, caller.getId());

        log.info("<<< generatePacket() packetId={}", packet.getId());
        return toDTO(packet);
    }

    // ── List packets ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<GeneratedPdfPacketDTO> listPackets(Long caseId, Long packageId) {
        User caller = currentUser();
        permissionService.requireAccess(caller, caseId, GrantScope.APPROVE_FORMS);
        return packetRepo.findByPackageIdOrderByCreatedAtDesc(packageId)
                .stream().map(this::toDTO).toList();
    }

    // ── Download ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ResponseEntity<Resource> downloadPacket(Long caseId, Long packageId, Long packetId) {
        User caller = currentUser();
        permissionService.requireAccess(caller, caseId, GrantScope.APPROVE_FORMS);

        GeneratedPdfPacket packet = packetRepo.findByIdAndPackageId(packetId, packageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Packet not found"));

        Path zipPath = storagePathResolver.asPath().resolve(packet.getPdfStorageKey());
        if (!Files.exists(zipPath)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Packet file not found on disk");
        }

        Resource resource = new FileSystemResource(zipPath);
        String filename = "filing-packet-" + caseId + "-" + packetId + ".zip";
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(resource);
    }

    // ── Approve packet ────────────────────────────────────────────────────────

    @Transactional
    public GeneratedPdfPacketDTO approvePacket(Long caseId, Long packageId, Long packetId) {
        User caller = currentUser();
        permissionService.requireAccess(caller, caseId, GrantScope.APPROVE_FORMS);

        GeneratedPdfPacket packet = packetRepo.findByIdAndPackageId(packetId, packageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Packet not found"));

        if ("ATTORNEY_APPROVED".equals(packet.getStatus())) {
            return toDTO(packet); // idempotent
        }

        packet.setStatus("ATTORNEY_APPROVED");
        packet.setAttorneyApprovedAt(LocalDateTime.now());
        packet.setAttorneyApprovedBy(caller.getId());
        packet = packetRepo.save(packet);

        ImmigrationCase immCase = caseRepo.findById(caseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found"));

        auditService.append(immCase, caller, "PDF_PACKET_APPROVED",
                "{\"packetId\":" + packet.getId() + "}", FeedVisibility.ATTORNEY_ONLY);

        // FormVersion audit: PACKET_APPROVED
        List<Map<String, Object>> fvUsed = parseMapList(packet.getFormVersionsUsedJson());
        for (Map<String, Object> entry : fvUsed) {
            FormVersionAuditEvent fvAudit = new FormVersionAuditEvent();
            fvAudit.setFormType((String) entry.get("formType"));
            fvAudit.setEditionDate((String) entry.get("editionDate"));
            fvAudit.setAction("PACKET_APPROVED");
            fvAudit.setPerformedByUserId(caller.getId());
            fvAudit.setDetail("{\"packetId\":" + packet.getId() + ",\"caseId\":" + caseId + "}");
            formVersionAuditRepo.save(fvAudit);
        }

        return toDTO(packet);
    }

    // ── PDF fill ──────────────────────────────────────────────────────────────

    private byte[] fillForm(String formType, FormVersion fv, byte[] pdfBytes,
                             Map<String, FilingPackageAnswer> answerMap,
                             List<Map<String, Object>> auditEntries) {
        Optional<FormFieldMapping> mappingOpt = questionRegistry.getFormMapping(formType);
        if (mappingOpt.isEmpty()) {
            log.warn("No form field mapping for {} — returning unfilled PDF", formType);
            return pdfBytes;
        }
        FormFieldMapping mapping = mappingOpt.get();

        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            if (acroForm != null && mapping.getSections() != null) {
                for (FormSectionMapping section : mapping.getSections().values()) {
                    if (section.getFields() == null) continue;
                    for (FormFieldEntry entry : section.getFields()) {
                        String questionKey = entry.getQuestionKey();
                        String pdfFieldName = entry.getPdfFieldName();

                        FilingPackageAnswer ans = answerMap.get(questionKey);
                        boolean filled = false;
                        String source = ans != null ? ans.getSource() : "none";

                        if (ans != null && ans.getValueJson() != null && !ans.getValueJson().isBlank()) {
                            CanonicalQuestion q = questionRegistry.findByKey(questionKey).orElse(null);
                            String value = (q != null && q.isEncrypt())
                                    ? encryptionService.decrypt(ans.getValueJson())
                                    : ans.getValueJson();

                            PDField field = acroForm.getField(pdfFieldName);
                            if (field != null) {
                                try {
                                    field.setValue(value);
                                    filled = true;
                                } catch (Exception e) {
                                    log.warn("Could not fill field {} on {}: {}", pdfFieldName, formType, e.getMessage());
                                }
                            }
                            value = null; // clear from memory immediately
                        }

                        auditEntries.add(new LinkedHashMap<>(Map.of(
                                "questionKey", questionKey,
                                "pdfField", pdfFieldName,
                                "source", source,
                                "versionId", fv.getId(),
                                "filled", filled,
                                "formType", formType
                        )));
                    }
                }
                acroForm.flatten();
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to fill PDF for " + formType + ": " + e.getMessage());
        }
    }

    // ── Cover sheet ───────────────────────────────────────────────────────────

    private byte[] buildCoverSheet(ImmigrationCase immCase,
                                    List<Map<String, Object>> formVersionsUsed,
                                    User caller) {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);

            PDType1Font fontBold    = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font fontRegular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float margin = 60;
                float y = 720;

                // Title
                cs.beginText();
                cs.setFont(fontBold, 18);
                cs.newLineAtOffset(margin, y);
                cs.showText("Filing Packet — Case " + immCase.getCaseNumber());
                cs.endText();

                y -= 28;
                cs.beginText();
                cs.setFont(fontRegular, 11);
                cs.newLineAtOffset(margin, y);
                cs.showText("Date Generated: "
                        + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMMM d, yyyy HH:mm")));
                cs.endText();

                y -= 18;
                cs.beginText();
                cs.setFont(fontRegular, 11);
                cs.newLineAtOffset(margin, y);
                cs.showText("Generated By: " + caller.getName());
                cs.endText();

                // Forms included
                y -= 32;
                cs.beginText();
                cs.setFont(fontBold, 12);
                cs.newLineAtOffset(margin, y);
                cs.showText("Forms Included:");
                cs.endText();

                for (Map<String, Object> fv : formVersionsUsed) {
                    y -= 18;
                    cs.beginText();
                    cs.setFont(fontRegular, 11);
                    cs.newLineAtOffset(margin + 16, y);
                    cs.showText(fv.get("formType") + "   Edition: " + fv.get("editionDate")
                            + "   (Version ID: " + fv.get("versionId") + ")");
                    cs.endText();
                }

                // Signature line
                y -= 48;
                cs.beginText();
                cs.setFont(fontBold, 11);
                cs.newLineAtOffset(margin, y);
                cs.showText("Attorney Signature: ___________________________________________");
                cs.endText();

                y -= 18;
                cs.beginText();
                cs.setFont(fontRegular, 9);
                cs.newLineAtOffset(margin, y);
                cs.showText("Date: " + LocalDate.now());
                cs.endText();

                // Footer note
                y -= 40;
                cs.beginText();
                cs.setFont(fontRegular, 9);
                cs.newLineAtOffset(margin, y);
                cs.showText("Review all forms carefully before submission to USCIS.");
                cs.endText();
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to build cover sheet: " + e.getMessage());
        }
    }

    // ── ZIP assembly ──────────────────────────────────────────────────────────

    private void buildZip(Path zipPath, byte[] coverSheet,
                           List<byte[]> formPdfs, List<String> formNames) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipPath.toFile()))) {
            zos.putNextEntry(new ZipEntry("00_cover-sheet.pdf"));
            zos.write(coverSheet);
            zos.closeEntry();

            for (int i = 0; i < formPdfs.size(); i++) {
                zos.putNextEntry(new ZipEntry(String.format("%02d_%s", i + 1, formNames.get(i))));
                zos.write(formPdfs.get(i));
                zos.closeEntry();
            }
        }
    }

    // ── DTO builder ───────────────────────────────────────────────────────────

    private GeneratedPdfPacketDTO toDTO(GeneratedPdfPacket p) {
        List<GeneratedPdfPacketDTO.FormVersionUsedDTO> fvUsed = parseMapList(p.getFormVersionsUsedJson())
                .stream()
                .map(m -> new GeneratedPdfPacketDTO.FormVersionUsedDTO(
                        (String) m.get("formType"),
                        m.get("versionId") instanceof Number n ? n.longValue() : null,
                        (String) m.get("editionDate")
                ))
                .toList();

        List<GeneratedPdfPacketDTO.GenerationAuditEntryDTO> audit =
                parseMapList(p.getGenerationAuditJson())
                .stream()
                .map(m -> new GeneratedPdfPacketDTO.GenerationAuditEntryDTO(
                        (String) m.get("questionKey"),
                        (String) m.get("pdfField"),
                        (String) m.get("source"),
                        m.get("versionId") instanceof Number n ? n.longValue() : null,
                        Boolean.TRUE.equals(m.get("filled")),
                        (String) m.get("formType")
                ))
                .toList();

        return new GeneratedPdfPacketDTO(
                p.getId(), p.getPackageId(), p.getCaseId(), fvUsed,
                p.getGeneratedAt(), p.getGeneratedByUserId(), p.getStatus(),
                p.getAttorneyApprovedAt(), p.getAttorneyApprovedBy(),
                p.getPdfStorageKey(), audit, p.getCreatedAt()
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<String> parseList(String json) {
        try {
            if (json == null || json.isBlank()) return List.of();
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Map<String, Object>> parseMapList(String json) {
        try {
            if (json == null || json.isBlank()) return List.of();
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }
}
