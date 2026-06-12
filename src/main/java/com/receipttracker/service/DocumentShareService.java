package com.receipttracker.service;

import com.receipttracker.dto.CreateDocumentShareRequest;
import com.receipttracker.dto.DocumentDTO;
import com.receipttracker.dto.DocumentShareDTO;
import com.receipttracker.model.*;
import com.receipttracker.repository.DocumentRepository;
import com.receipttracker.repository.DocumentShareRepository;
import com.receipttracker.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class DocumentShareService {

    private static final Logger log = LoggerFactory.getLogger(DocumentShareService.class);

    @Autowired private DocumentShareRepository shareRepo;
    @Autowired private DocumentRepository documentRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private DocumentService documentService;
    @Autowired private EmailService emailService;
    @Autowired private FeatureEntitlementService entitlement;

    @Value("${app.frontend.url:http://localhost:4200}")
    private String frontendUrl;

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        OAuth2User principal = (OAuth2User) auth.getPrincipal();
        String googleId = principal.getAttribute("sub");
        return userRepo.findByGoogleId(googleId)
                .orElseThrow(() -> new RuntimeException("Authenticated user not found"));
    }

    // ── Create share ────────────────────────────────────────────────────────

    @Transactional
    public DocumentShareDTO createShare(CreateDocumentShareRequest req) {
        log.info(">>> createShare recipient={} docs={}", req.getRecipientEmail(), req.getDocumentIds());
        entitlement.requireFeature(AppFeature.DOCUMENT_VAULT);

        User owner = currentUser();

        if (req.getRecipientEmail() == null || !req.getRecipientEmail().contains("@")) {
            throw new RuntimeException("Invalid recipient email");
        }
        if (req.getDocumentIds() == null || req.getDocumentIds().isEmpty()) {
            throw new RuntimeException("At least one document must be selected");
        }
        int expiryDays = Math.min(Math.max(req.getExpiryDays(), 1), 30);

        // Verify ownership of all documents
        List<Document> docs = req.getDocumentIds().stream().map(id -> {
            Document d = documentRepo.findById(id)
                    .orElseThrow(() -> new RuntimeException("Document not found: " + id));
            if (!d.getUser().getId().equals(owner.getId())) {
                throw new RuntimeException("Access denied for document " + id);
            }
            return d;
        }).collect(Collectors.toList());

        DocumentShare share = new DocumentShare();
        share.setOwner(owner);
        share.setRecipientEmail(req.getRecipientEmail().trim().toLowerCase());
        share.setRecipientName(req.getRecipientName());
        share.setPurpose(req.getPurpose());
        share.setMessage(req.getMessage());
        share.setExpiresAt(LocalDateTime.now().plusDays(expiryDays));
        share.setDocuments(docs);

        DocumentShare saved = shareRepo.save(share);

        String shareUrl = frontendUrl + "/documents/shared/" + saved.getShareToken();
        emailService.sendDocumentShare(
                saved.getRecipientEmail(),
                saved.getRecipientName(),
                owner.getName(),
                saved.getPurpose(),
                saved.getMessage(),
                docs.size(),
                shareUrl,
                expiryDays
        );

        log.info("<<< createShare shareId={} token=[REDACTED]", saved.getId());
        return toDTO(saved);
    }

    // ── Public access via token ─────────────────────────────────────────────

    @Transactional
    public DocumentShareDTO getByToken(String token) {
        DocumentShare share = shareRepo.findByShareToken(token)
                .orElseThrow(() -> new RuntimeException("Invalid or expired share link"));

        if (share.getExpiresAt() != null && share.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("This share link has expired");
        }

        // Mark as accessed on first open
        if (!share.isAccessed()) {
            share.setAccessed(true);
            share.setAccessedAt(LocalDateTime.now());
            shareRepo.save(share);
        }
        return toDTO(share);
    }

    /** Stream a single document file via share token — verifies the doc is in the share. */
    @Transactional(readOnly = true)
    public Resource downloadViaToken(String token, Long documentId) throws IOException {
        DocumentShare share = shareRepo.findByShareToken(token)
                .orElseThrow(() -> new RuntimeException("Invalid or expired share link"));

        if (share.getExpiresAt() != null && share.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("This share link has expired");
        }

        Document doc = share.getDocuments().stream()
                .filter(d -> d.getId().equals(documentId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Document not in this share"));

        return documentService.downloadByPath(doc.getUser().getId(), doc.getStoredFileName());
    }

    // ── Owner views their sent shares ───────────────────────────────────────

    @Transactional(readOnly = true)
    public List<DocumentShareDTO> myShares() {
        entitlement.requireFeature(AppFeature.DOCUMENT_VAULT);
        User owner = currentUser();
        return shareRepo.findByOwnerOrderBySharedAtDesc(owner).stream()
                .map(this::toDTO).collect(Collectors.toList());
    }

    // ── Mapping ─────────────────────────────────────────────────────────────

    private DocumentShareDTO toDTO(DocumentShare s) {
        DocumentShareDTO dto = new DocumentShareDTO();
        dto.setId(s.getId());
        dto.setRecipientEmail(s.getRecipientEmail());
        dto.setRecipientName(s.getRecipientName());
        dto.setPurpose(s.getPurpose());
        dto.setMessage(s.getMessage());
        dto.setShareToken(s.getShareToken());
        dto.setExpiresAt(s.getExpiresAt());
        dto.setSharedAt(s.getSharedAt());
        dto.setAccessed(s.isAccessed());
        dto.setExpired(s.getExpiresAt() != null && s.getExpiresAt().isBefore(LocalDateTime.now()));
        if (s.getDocuments() != null) {
            dto.setDocuments(s.getDocuments().stream()
                    .map(documentService::toDTO).collect(Collectors.toList()));
        }
        return dto;
    }
}
