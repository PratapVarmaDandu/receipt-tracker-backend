package com.receipttracker.service;

import com.receipttracker.config.StoragePathResolver;
import com.receipttracker.dto.DocumentDTO;
import com.receipttracker.dto.DocumentNextStepDTO;
import com.receipttracker.model.*;
import com.receipttracker.repository.DocumentNextStepRepository;
import com.receipttracker.repository.DocumentRepository;
import com.receipttracker.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    /** Allowed MIME types — reject executables and unknown types for security. */
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
        "application/pdf",
        "image/jpeg", "image/png", "image/gif", "image/webp", "image/heic", "image/heif",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "text/plain"
    );

    /** Safe file extensions matching the MIME allowlist. */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
        "pdf", "jpg", "jpeg", "png", "gif", "webp", "heic", "heif",
        "doc", "docx", "xls", "xlsx", "txt"
    );

    @Autowired private DocumentRepository documentRepo;
    @Autowired private DocumentNextStepRepository nextStepRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private StoragePathResolver storagePathResolver;
    @Autowired private FeatureEntitlementService entitlement;

    // ── User resolution ─────────────────────────────────────────────────────

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        OAuth2User principal = (OAuth2User) auth.getPrincipal();
        String googleId = principal.getAttribute("sub");
        return userRepo.findByGoogleId(googleId)
                .orElseThrow(() -> new RuntimeException("Authenticated user not found"));
    }

    // ── Storage path ────────────────────────────────────────────────────────

    private Path docDir(Long userId) throws IOException {
        Path dir = storagePathResolver.asPath().resolve("documents").resolve(String.valueOf(userId));
        Files.createDirectories(dir);
        return dir;
    }

    // ── Upload ──────────────────────────────────────────────────────────────

    @Transactional
    public DocumentDTO upload(MultipartFile file,
                              String title,
                              String categoryStr,
                              String subcategory,
                              Integer documentYear,
                              String expiryDateStr,
                              String notes) throws IOException {

        log.info(">>> upload title={} category={}", title, categoryStr);
        entitlement.requireFeature(AppFeature.DOCUMENT_VAULT);
        User user = currentUser();

        // ── Security: validate file type ─────────────────────────────────
        String contentType = file.getContentType() != null ? file.getContentType().toLowerCase() : "";
        if (!ALLOWED_MIME_TYPES.contains(contentType)) {
            throw new RuntimeException("File type not allowed: " + contentType);
        }
        String originalName = Objects.requireNonNullElse(file.getOriginalFilename(), "document");
        String ext = originalName.contains(".")
                ? originalName.substring(originalName.lastIndexOf('.') + 1).toLowerCase()
                : "";
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new RuntimeException("File extension not allowed: " + ext);
        }

        // ── Store with UUID filename to prevent path traversal ────────────
        String storedName = UUID.randomUUID() + "." + ext;
        Path dest = docDir(user.getId()).resolve(storedName);
        Files.copy(file.getInputStream(), dest);

        // ── Persist ───────────────────────────────────────────────────────
        DocumentCategory category = DocumentCategory.valueOf(categoryStr.toUpperCase());
        LocalDate expiry = (expiryDateStr != null && !expiryDateStr.isBlank())
                ? LocalDate.parse(expiryDateStr) : null;

        Document doc = new Document();
        doc.setTitle(title != null && !title.isBlank() ? title.trim() : originalName);
        doc.setOriginalFileName(originalName);
        doc.setStoredFileName(storedName);
        doc.setContentType(contentType);
        doc.setFileSize(file.getSize());
        doc.setCategory(category);
        doc.setSubcategory(subcategory);
        doc.setDocumentYear(documentYear);
        doc.setExpiryDate(expiry);
        doc.setNotes(notes);
        doc.setUser(user);

        Document saved = documentRepo.save(doc);
        log.info("<<< upload saved documentId={}", saved.getId());
        return toDTO(saved);
    }

    // ── Download ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Resource download(Long documentId) throws IOException {
        entitlement.requireFeature(AppFeature.DOCUMENT_VAULT);
        User caller = currentUser();
        Document doc = documentRepo.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Document not found: " + documentId));
        if (!doc.getUser().getId().equals(caller.getId())) {
            throw new RuntimeException("Access denied");
        }
        Path file = docDir(doc.getUser().getId()).resolve(doc.getStoredFileName());
        if (!Files.exists(file)) {
            throw new RuntimeException("File not found in storage");
        }
        return new FileSystemResource(file);
    }

    /** Download by share token — called from DocumentShareService; no auth check here. */
    @Transactional(readOnly = true)
    public Resource downloadByPath(Long userId, String storedFileName) throws IOException {
        Path file = docDir(userId).resolve(storedFileName);
        if (!Files.exists(file)) {
            throw new RuntimeException("File not found in storage");
        }
        return new FileSystemResource(file);
    }

    // ── List / query ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<DocumentDTO> list(String categoryStr, int page, int size) {
        entitlement.requireFeature(AppFeature.DOCUMENT_VAULT);
        User user = currentUser();
        if (categoryStr != null && !categoryStr.isBlank()) {
            DocumentCategory cat = DocumentCategory.valueOf(categoryStr.toUpperCase());
            return documentRepo
                    .findByUserAndCategoryAndArchivedFalseOrderByDocumentYearDescUploadedAtDesc(user, cat)
                    .stream().map(this::toDTO).collect(Collectors.toList());
        }
        return documentRepo
                .findByUserAndArchivedFalseOrderByUploadedAtDesc(user, PageRequest.of(page, size))
                .stream().map(this::toDTO).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public DocumentDTO getById(Long id) {
        entitlement.requireFeature(AppFeature.DOCUMENT_VAULT);
        User caller = currentUser();
        Document doc = documentRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Document not found: " + id));
        if (!doc.getUser().getId().equals(caller.getId())) {
            throw new RuntimeException("Access denied");
        }
        return toDTO(doc);
    }

    @Transactional(readOnly = true)
    public List<DocumentDTO> getExpiringSoon(int days) {
        entitlement.requireFeature(AppFeature.DOCUMENT_VAULT);
        User user = currentUser();
        LocalDate today = LocalDate.now();
        return documentRepo.findExpiringBetween(user, today, today.plusDays(days))
                .stream().map(this::toDTO).collect(Collectors.toList());
    }

    /** Dashboard summary: counts by category + expiring-soon count. */
    @Transactional(readOnly = true)
    public Map<String, Object> getSummary() {
        entitlement.requireFeature(AppFeature.DOCUMENT_VAULT);
        User user = currentUser();
        List<Document> all = documentRepo.findAllActiveByUser(user);
        LocalDate today = LocalDate.now();
        LocalDate soon = today.plusDays(90);

        Map<String, Long> byCat = Arrays.stream(DocumentCategory.values())
                .collect(Collectors.toMap(
                        Enum::name,
                        cat -> all.stream().filter(d -> d.getCategory() == cat).count()
                ));

        long expiring = all.stream()
                .filter(d -> d.getExpiryDate() != null
                        && !d.getExpiryDate().isBefore(today)
                        && !d.getExpiryDate().isAfter(soon))
                .count();

        long expired = all.stream()
                .filter(d -> d.getExpiryDate() != null && d.getExpiryDate().isBefore(today))
                .count();

        long pendingSteps = nextStepRepo.findPendingByUser(user).size();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", all.size());
        summary.put("expiringSoon", expiring);
        summary.put("expired", expired);
        summary.put("pendingNextSteps", pendingSteps);
        summary.put("byCategory", byCat);
        return summary;
    }

    // ── Update ──────────────────────────────────────────────────────────────

    @Transactional
    public DocumentDTO update(Long id, String title, String subcategory,
                               Integer documentYear, String expiryDateStr, String notes) {
        entitlement.requireFeature(AppFeature.DOCUMENT_VAULT);
        User caller = currentUser();
        Document doc = documentRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Document not found: " + id));
        if (!doc.getUser().getId().equals(caller.getId())) throw new RuntimeException("Access denied");

        if (title != null && !title.isBlank()) doc.setTitle(title.trim());
        if (subcategory != null) doc.setSubcategory(subcategory);
        if (documentYear != null) doc.setDocumentYear(documentYear);
        doc.setExpiryDate(expiryDateStr != null && !expiryDateStr.isBlank()
                ? LocalDate.parse(expiryDateStr) : null);
        if (notes != null) doc.setNotes(notes);
        return toDTO(documentRepo.save(doc));
    }

    @Transactional
    public DocumentDTO archive(Long id) {
        entitlement.requireFeature(AppFeature.DOCUMENT_VAULT);
        User caller = currentUser();
        Document doc = documentRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Document not found: " + id));
        if (!doc.getUser().getId().equals(caller.getId())) throw new RuntimeException("Access denied");
        doc.setArchived(true);
        return toDTO(documentRepo.save(doc));
    }

    @Transactional
    public void delete(Long id) throws IOException {
        entitlement.requireFeature(AppFeature.DOCUMENT_VAULT);
        User caller = currentUser();
        Document doc = documentRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Document not found: " + id));
        if (!doc.getUser().getId().equals(caller.getId())) throw new RuntimeException("Access denied");

        // Delete file from storage (non-fatal)
        try {
            Path file = docDir(doc.getUser().getId()).resolve(doc.getStoredFileName());
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Failed to delete document file: {}", e.getMessage());
        }
        documentRepo.delete(doc);
        log.info("Deleted document id={}", id);
    }

    // ── Next steps ──────────────────────────────────────────────────────────

    @Transactional
    public DocumentNextStepDTO addNextStep(Long documentId, String title,
                                           String description, String dueDateStr) {
        entitlement.requireFeature(AppFeature.DOCUMENT_VAULT);
        User caller = currentUser();
        Document doc = documentRepo.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Document not found: " + documentId));
        if (!doc.getUser().getId().equals(caller.getId())) throw new RuntimeException("Access denied");

        DocumentNextStep step = new DocumentNextStep();
        step.setDocument(doc);
        step.setTitle(title);
        step.setDescription(description);
        if (dueDateStr != null && !dueDateStr.isBlank()) {
            step.setDueDate(LocalDate.parse(dueDateStr));
        }
        return toStepDTO(nextStepRepo.save(step));
    }

    @Transactional
    public DocumentNextStepDTO completeNextStep(Long stepId) {
        entitlement.requireFeature(AppFeature.DOCUMENT_VAULT);
        User caller = currentUser();
        DocumentNextStep step = nextStepRepo.findById(stepId)
                .orElseThrow(() -> new RuntimeException("Step not found: " + stepId));
        if (!step.getDocument().getUser().getId().equals(caller.getId())) {
            throw new RuntimeException("Access denied");
        }
        step.setCompleted(true);
        step.setCompletedAt(LocalDateTime.now());
        return toStepDTO(nextStepRepo.save(step));
    }

    @Transactional
    public void deleteNextStep(Long stepId) {
        entitlement.requireFeature(AppFeature.DOCUMENT_VAULT);
        User caller = currentUser();
        DocumentNextStep step = nextStepRepo.findById(stepId)
                .orElseThrow(() -> new RuntimeException("Step not found: " + stepId));
        if (!step.getDocument().getUser().getId().equals(caller.getId())) {
            throw new RuntimeException("Access denied");
        }
        nextStepRepo.delete(step);
    }

    // ── Mapping ─────────────────────────────────────────────────────────────

    DocumentDTO toDTO(Document doc) {
        DocumentDTO dto = new DocumentDTO();
        dto.setId(doc.getId());
        dto.setTitle(doc.getTitle());
        dto.setOriginalFileName(doc.getOriginalFileName());
        dto.setContentType(doc.getContentType());
        dto.setFileSize(doc.getFileSize());
        dto.setCategory(doc.getCategory());
        dto.setSubcategory(doc.getSubcategory());
        dto.setDocumentYear(doc.getDocumentYear());
        dto.setExpiryDate(doc.getExpiryDate());
        dto.setNotes(doc.getNotes());
        dto.setArchived(doc.isArchived());
        dto.setUploadedAt(doc.getUploadedAt());
        dto.setUpdatedAt(doc.getUpdatedAt());

        // Compute status from expiry date
        if (doc.getExpiryDate() != null) {
            LocalDate today = LocalDate.now();
            long days = ChronoUnit.DAYS.between(today, doc.getExpiryDate());
            dto.setDaysUntilExpiry((int) days);
            if (days < 0)       dto.setStatus("EXPIRED");
            else if (days <= 90) dto.setStatus("EXPIRING_SOON");
            else                 dto.setStatus("ACTIVE");
        } else {
            dto.setStatus("ACTIVE");
        }

        if (doc.getNextSteps() != null) {
            dto.setNextSteps(doc.getNextSteps().stream()
                    .map(this::toStepDTO).collect(Collectors.toList()));
        }
        return dto;
    }

    private DocumentNextStepDTO toStepDTO(DocumentNextStep s) {
        DocumentNextStepDTO dto = new DocumentNextStepDTO();
        dto.setId(s.getId());
        dto.setDocumentId(s.getDocument().getId());
        dto.setTitle(s.getTitle());
        dto.setDescription(s.getDescription());
        dto.setDueDate(s.getDueDate());
        dto.setCompleted(s.isCompleted());
        dto.setCompletedAt(s.getCompletedAt());
        dto.setCreatedAt(s.getCreatedAt());

        if (!s.isCompleted() && s.getDueDate() != null) {
            long days = ChronoUnit.DAYS.between(LocalDate.now(), s.getDueDate());
            if (days < 0)       dto.setUrgency("OVERDUE");
            else if (days <= 7) dto.setUrgency("DUE_SOON");
            else                dto.setUrgency("UPCOMING");
        } else {
            dto.setUrgency("DONE");
        }
        return dto;
    }
}
