package com.receipttracker.immigration.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.receipttracker.config.StoragePathResolver;
import com.receipttracker.immigration.dto.FormVersionAuditEventDTO;
import com.receipttracker.immigration.dto.FormVersionDTO;
import com.receipttracker.immigration.model.*;
import com.receipttracker.immigration.model.question.FormFieldEntry;
import com.receipttracker.immigration.model.question.FormFieldMapping;
import com.receipttracker.immigration.model.question.FormSectionMapping;
import com.receipttracker.immigration.repository.FormVersionAuditEventRepository;
import com.receipttracker.immigration.repository.FormVersionRepository;
import com.receipttracker.immigration.repository.ImmOrgMemberRepository;
import com.receipttracker.immigration.repository.ImmOrgRepository;
import com.receipttracker.model.User;
import com.receipttracker.repository.UserRepository;
import com.receipttracker.service.EmailService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Transactional
public class FormVersionService {

    private static final Logger log = LoggerFactory.getLogger(FormVersionService.class);

    private static final String USCIS_FORMS_URL = "https://www.uscis.gov/forms/all-forms";

    // Maps FormType.name() → USCIS form number as it appears on the page (e.g. "I-129")
    private static final Map<String, String> FORM_NUMBERS = Map.of(
        "I129",  "I-129",
        "I485",  "I-485",
        "I765",  "I-765",
        "I131",  "I-131",
        "I140",  "I-140",
        "I539",  "I-539",
        "G28",   "G-28",
        "I290B", "I-290B",
        "I693",  "I-693"
        // DS160 is a DOS form — not tracked on USCIS forms page
    );

    // Pattern: two-digit month / two-digit day / two or four-digit year
    private static final Pattern EDITION_DATE_PATTERN = Pattern.compile("(\\d{2}/\\d{2}/\\d{2,4})");

    @Autowired private FormVersionRepository versionRepo;
    @Autowired private FormVersionAuditEventRepository auditRepo;
    @Autowired private ImmOrgRepository orgRepo;
    @Autowired private ImmOrgMemberRepository orgMemberRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private CanonicalQuestionRegistry questionRegistry;
    @Autowired private EmailService emailService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private StoragePathResolver storagePathResolver;

    // ── Auth helpers ──────────────────────────────────────────────────────────

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        OAuth2User principal = (OAuth2User) auth.getPrincipal();
        String email = principal.getAttribute("email");
        return userRepo.findByEmail(email)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }

    private void requireAttorneyOrOwner(User caller) {
        List<ImmOrgMember> memberships = orgMemberRepo.findByUserIdAndStatus(caller.getId(), ImmOrgMemberStatus.ACTIVE);
        boolean hasAccess = memberships.stream().anyMatch(m -> {
            Optional<ImmOrg> org = orgRepo.findById(m.getImmOrgId());
            return org.isPresent()
                && org.get().getOrgType() == ImmOrgType.LAW_FIRM
                && (m.getRole() == ImmOrgMemberRole.ATTORNEY || m.getRole() == ImmOrgMemberRole.OWNER);
        });
        if (!hasAccess) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Requires ATTORNEY or OWNER role in a law firm");
        }
    }

    // ── Query endpoints ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, List<FormVersionDTO>> getAll() {
        requireAttorneyOrOwner(currentUser());
        return versionRepo.findAllByOrderByCreatedAtDesc().stream()
            .collect(Collectors.groupingBy(
                FormVersion::getFormType,
                LinkedHashMap::new,
                Collectors.mapping(fv -> toDTO(fv, null), Collectors.toList())
            ));
    }

    @Transactional(readOnly = true)
    public FormVersionDTO getById(Long id) {
        requireAttorneyOrOwner(currentUser());
        FormVersion fv = versionRepo.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Form version not found"));
        List<FormVersionAuditEventDTO> audit = auditRepo
            .findByFormTypeOrderByCreatedAtDesc(fv.getFormType())
            .stream().limit(20).map(this::toAuditDTO).toList();
        return toDTO(fv, audit);
    }

    // ── Write endpoints ───────────────────────────────────────────────────────

    public FormVersionDTO approve(Long id) {
        User caller = currentUser();
        requireAttorneyOrOwner(caller);
        FormVersion fv = versionRepo.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Form version not found"));
        if (!"PENDING_REVIEW".equals(fv.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Only PENDING_REVIEW versions can be approved");
        }
        if (!fv.isFieldMappingVerified()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Field mapping must be verified before approving. Upload a mapping file first.");
        }
        // Deprecate the currently APPROVED version for this formType
        final String newEdition = fv.getEditionDate();
        versionRepo.findFirstByFormTypeAndStatusOrderByCreatedAtDesc(fv.getFormType(), "APPROVED")
            .ifPresent(prev -> {
                prev.setStatus("DEPRECATED");
                versionRepo.save(prev);
                saveAudit(prev.getFormType(), prev.getEditionDate(), "DEPRECATED", caller.getId(),
                    "Superseded by edition " + newEdition);
            });
        fv.setStatus("APPROVED");
        fv.setApprovedByUserId(caller.getId());
        fv.setApprovedAt(LocalDateTime.now());
        fv = versionRepo.save(fv);
        saveAudit(fv.getFormType(), fv.getEditionDate(), "APPROVED", caller.getId(),
            "Approved by " + caller.getEmail());
        return toDTO(fv, null);
    }

    public FormVersionDTO uploadMapping(Long id, MultipartFile file) {
        User caller = currentUser();
        requireAttorneyOrOwner(caller);
        FormVersion fv = versionRepo.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Form version not found"));
        try {
            String json = new String(file.getBytes(), StandardCharsets.UTF_8);
            FormFieldMapping mapping = objectMapper.readValue(json, FormFieldMapping.class);
            if (mapping.getSections() == null || mapping.getSections().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mapping JSON has no sections");
            }
            // Validate all questionKeys exist in canonical registry
            List<String> unknownKeys = new ArrayList<>();
            for (FormSectionMapping section : mapping.getSections().values()) {
                if (section.getFields() != null) {
                    for (FormFieldEntry entry : section.getFields()) {
                        if (entry.getQuestionKey() != null
                                && questionRegistry.findByKey(entry.getQuestionKey()).isEmpty()) {
                            unknownKeys.add(entry.getQuestionKey());
                        }
                    }
                }
            }
            if (!unknownKeys.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown canonical question keys: " + unknownKeys);
            }
            fv.setProposedMappingJson(json);
            fv.setFieldMappingVerified(true);
            fv = versionRepo.save(fv);
            saveAudit(fv.getFormType(), fv.getEditionDate(), "MAPPING_UPDATED", caller.getId(),
                "Mapping uploaded and verified: " + file.getOriginalFilename());
            return toDTO(fv, null);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid mapping JSON: " + e.getMessage());
        }
    }

    // ── Scheduler entry point ─────────────────────────────────────────────────

    public void checkForUpdates() {
        log.info("FormVersionScheduler: checking USCIS form editions");
        String html = fetchUscisPage();
        for (FormType ft : FormType.values()) {
            String formNumber = FORM_NUMBERS.get(ft.name());
            if (formNumber == null) continue;
            try {
                String edition = html != null ? parseEditionDate(html, formNumber) : null;
                processForm(ft.name(), ft.displayName, formNumber, edition);
            } catch (Exception e) {
                log.error("Error checking form {}: {}", ft.name(), e.getMessage(), e);
                saveAudit(ft.name(), null, "CHECK_ERROR", null,
                    "Scheduler error: " + e.getMessage());
            }
        }
        log.info("FormVersionScheduler: check complete");
    }

    private void processForm(String formType, String displayName, String formNumber, String edition)
            throws Exception {
        if (edition == null) {
            saveAudit(formType, null, "CHECK_NO_CHANGE", null,
                "Edition date not found on USCIS forms page for " + formNumber);
            return;
        }
        // Already have an approved version at this edition?
        Optional<FormVersion> approvedVersion =
            versionRepo.findFirstByFormTypeAndStatusOrderByCreatedAtDesc(formType, "APPROVED");
        if (approvedVersion.isPresent() && edition.equals(approvedVersion.get().getEditionDate())) {
            saveAudit(formType, edition, "CHECK_NO_CHANGE", null,
                "Edition " + edition + " already approved");
            return;
        }
        // Already queued for review at this edition?
        boolean alreadyPending = versionRepo.findByFormTypeOrderByCreatedAtDesc(formType).stream()
            .anyMatch(v -> edition.equals(v.getEditionDate()) && "PENDING_REVIEW".equals(v.getStatus()));
        if (alreadyPending) {
            saveAudit(formType, edition, "CHECK_NO_CHANGE", null,
                "Edition " + edition + " already pending review");
            return;
        }
        byte[] pdfBytes = downloadFormPdf(formNumber);
        List<String> fields = extractPdfFields(pdfBytes);
        String storageKey = storePdf(formType, edition, pdfBytes);

        FormVersion fv = new FormVersion();
        fv.setFormType(formType);
        fv.setEditionDate(edition);
        fv.setDownloadedAt(LocalDateTime.now());
        fv.setPdfStorageKey(storageKey);
        fv.setStatus("PENDING_REVIEW");
        fv.setPdfFieldNamesJson(objectMapper.writeValueAsString(fields));
        versionRepo.save(fv);

        saveAudit(formType, edition, "DOWNLOADED", null,
            "New edition " + edition + " for " + formNumber + "; "
            + fields.size() + " AcroForm fields extracted");
        notifyAttorneys(formType, edition);
    }

    // ── USCIS page fetching and parsing ───────────────────────────────────────

    private String fetchUscisPage() {
        try {
            HttpClient client = HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .build();
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(USCIS_FORMS_URL))
                .header("User-Agent", "Mozilla/5.0 (compatible; ImmigrationTracker/1.0)")
                .GET()
                .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                log.info("Fetched USCIS forms page: {} chars", resp.body().length());
                return resp.body();
            }
            log.warn("USCIS page returned status {}", resp.statusCode());
        } catch (Exception e) {
            log.warn("Could not fetch USCIS forms page: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Searches the USCIS forms HTML for the edition date of the given form number.
     * Looks for the form number string (e.g. "I-129") then finds the first
     * MM/DD/YY(YY) date pattern within the following 2000 characters.
     */
    private String parseEditionDate(String html, String formNumber) {
        // Normalise to upper case for matching
        int idx = html.indexOf(formNumber);
        if (idx < 0) {
            // Try lowercase variant
            idx = html.toLowerCase().indexOf(formNumber.toLowerCase());
        }
        if (idx < 0) return null;
        // Scan up to 2000 chars after the form number for a date pattern
        String window = html.substring(idx, Math.min(idx + 2000, html.length()));
        Matcher m = EDITION_DATE_PATTERN.matcher(window);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    // ── PDF download and processing ───────────────────────────────────────────

    private byte[] downloadFormPdf(String formNumber) {
        // USCIS canonical PDF URL pattern
        String pdfUrl = "https://www.uscis.gov/sites/default/files/document/forms/"
            + formNumber.toLowerCase().replace("-", "") + ".pdf";
        try {
            HttpClient client = HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .build();
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(pdfUrl))
                .header("User-Agent", "Mozilla/5.0 (compatible; ImmigrationTracker/1.0)")
                .GET()
                .build();
            HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 200) {
                log.info("Downloaded PDF for {} ({} bytes)", formNumber, resp.body().length);
                return resp.body();
            }
            log.warn("PDF download for {} returned status {}", formNumber, resp.statusCode());
        } catch (Exception e) {
            log.warn("Could not download PDF for {}: {}", formNumber, e.getMessage());
        }
        return null;
    }

    private List<String> extractPdfFields(byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length == 0) return List.of();
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            if (acroForm == null) return List.of();
            return acroForm.getFields().stream()
                .map(PDField::getFullyQualifiedName)
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        } catch (Exception e) {
            log.warn("Could not extract AcroForm fields from PDF: {}", e.getMessage());
            return List.of();
        }
    }

    private String storePdf(String formType, String edition, byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length == 0) return null;
        try {
            String safeEdition = edition.replace("/", "-");
            Path dir = storagePathResolver.asPath().resolve("form-versions").resolve(formType);
            Files.createDirectories(dir);
            Path file = dir.resolve(safeEdition + ".pdf");
            Files.write(file, pdfBytes);
            return "form-versions/" + formType + "/" + safeEdition + ".pdf";
        } catch (IOException e) {
            log.warn("Could not store PDF for {} edition {}: {}", formType, edition, e.getMessage());
            return null;
        }
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private void notifyAttorneys(String formType, String editionDate) {
        List<ImmOrg> firms = orgRepo.findAll().stream()
            .filter(o -> o.getOrgType() == ImmOrgType.LAW_FIRM)
            .toList();
        Set<String> notified = new HashSet<>();
        for (ImmOrg firm : firms) {
            orgMemberRepo.findByImmOrgId(firm.getId()).stream()
                .filter(m -> m.getStatus() == ImmOrgMemberStatus.ACTIVE)
                .filter(m -> m.getRole() == ImmOrgMemberRole.ATTORNEY
                          || m.getRole() == ImmOrgMemberRole.OWNER)
                .map(ImmOrgMember::getEmail)
                .filter(notified::add)
                .forEach(email -> {
                    String text = formType + " has been updated to edition " + editionDate + ".\n\n"
                        + "Please log in and navigate to Immigration > Form Versions to review "
                        + "the new PDF fields and verify the field mapping before approving.";
                    emailService.sendSimpleEmail(email,
                        "Form Update: " + formType + " edition " + editionDate, text);
                });
        }
    }

    // ── Audit helper ──────────────────────────────────────────────────────────

    private void saveAudit(String formType, String editionDate, String action,
                           Long performedByUserId, String detail) {
        FormVersionAuditEvent evt = new FormVersionAuditEvent();
        evt.setFormType(formType);
        evt.setEditionDate(editionDate);
        evt.setAction(action);
        evt.setPerformedByUserId(performedByUserId);
        evt.setDetail(detail);
        auditRepo.save(evt);
    }

    // ── DTO mapping ───────────────────────────────────────────────────────────

    private FormVersionDTO toDTO(FormVersion fv, List<FormVersionAuditEventDTO> audit) {
        List<String> fieldNames = List.of();
        if (fv.getPdfFieldNamesJson() != null) {
            try {
                fieldNames = objectMapper.readValue(fv.getPdfFieldNamesJson(),
                    new TypeReference<List<String>>() {});
            } catch (Exception e) {
                log.warn("Could not parse pdfFieldNamesJson for version {}", fv.getId());
            }
        }
        String displayName = resolveDisplayName(fv.getFormType());
        return new FormVersionDTO(
            fv.getId(), fv.getFormType(), displayName, fv.getEditionDate(),
            fv.getDownloadedAt(), fv.getPdfStorageKey(), fv.getStatus(),
            fv.getApprovedByUserId(), fv.getApprovedAt(),
            fv.isFieldMappingVerified(), fieldNames,
            fv.getReleaseNotes(), fv.getProposedMappingJson() != null,
            fv.getCreatedAt(), audit
        );
    }

    private FormVersionAuditEventDTO toAuditDTO(FormVersionAuditEvent evt) {
        return new FormVersionAuditEventDTO(
            evt.getId(), evt.getFormType(), evt.getEditionDate(), evt.getAction(),
            evt.getPerformedByUserId(), evt.getDetail(), evt.getCreatedAt()
        );
    }

    private String resolveDisplayName(String formType) {
        try {
            return FormType.valueOf(formType).displayName;
        } catch (IllegalArgumentException e) {
            return formType;
        }
    }
}
