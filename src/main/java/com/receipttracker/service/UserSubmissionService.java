package com.receipttracker.service;

import com.receipttracker.config.StoragePathResolver;
import com.receipttracker.dto.UserSubmissionDTO;
import com.receipttracker.model.SubmissionType;
import com.receipttracker.model.User;
import com.receipttracker.model.UserSubmission;
import com.receipttracker.repository.UserRepository;
import com.receipttracker.repository.UserSubmissionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class UserSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(UserSubmissionService.class);

    private static final long MAX_ATTACHMENT_SIZE = 5_000_000; // 5MB manual cap (Spring's global limit is 25MB)

    /** Feedback attachments — broad allow-list, mirrors Document Vault's. */
    private static final Set<String> BROAD_MIME_TYPES = Set.of(
        "application/pdf",
        "image/jpeg", "image/png", "image/gif", "image/webp", "image/heic", "image/heif",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "text/plain"
    );
    private static final Set<String> BROAD_EXTENSIONS = Set.of(
        "pdf", "jpg", "jpeg", "png", "gif", "webp", "heic", "heif",
        "doc", "docx", "xls", "xlsx", "txt"
    );

    /** Bug report attachments — screenshots only. */
    private static final Set<String> IMAGE_MIME_TYPES = Set.of(
        "image/jpeg", "image/png", "image/gif", "image/webp", "image/heic", "image/heif"
    );
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
        "jpg", "jpeg", "png", "gif", "webp", "heic", "heif"
    );

    @Autowired private UserSubmissionRepository submissionRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private StoragePathResolver storagePathResolver;
    @Autowired private EmailService emailService;

    @Value("${platform.owner.email:}")
    private String platformOwnerEmail;

    // ── User resolution ─────────────────────────────────────────────────────

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        OAuth2User principal = (OAuth2User) auth.getPrincipal();
        String googleId = principal.getAttribute("sub");
        return userRepo.findByGoogleId(googleId)
                .orElseThrow(() -> new RuntimeException("Authenticated user not found"));
    }

    // ── Storage path ────────────────────────────────────────────────────────

    private Path submissionDir(Long userId) throws IOException {
        Path dir = storagePathResolver.asPath().resolve("submissions").resolve(String.valueOf(userId));
        Files.createDirectories(dir);
        return dir;
    }

    // ── Create ──────────────────────────────────────────────────────────────

    @Transactional
    public UserSubmissionDTO create(String typeStr, String message, MultipartFile file, String clientLogJson) throws IOException {
        log.info(">>> create submission type={}", typeStr);
        User user = currentUser();

        if (message == null || message.isBlank()) {
            throw new RuntimeException("Message is required");
        }
        SubmissionType type;
        try {
            type = SubmissionType.valueOf(typeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Invalid submission type: " + typeStr);
        }

        UserSubmission submission = new UserSubmission();
        submission.setUser(user);
        submission.setType(type);
        submission.setMessage(message.trim());
        submission.setClientLogJson(clientLogJson);

        if (file != null && !file.isEmpty()) {
            storeAttachment(submission, type, file);
        }

        UserSubmission saved = submissionRepo.save(submission);
        log.info("<<< create submission saved id={} type={}", saved.getId(), type);

        notifyPlatformOwner(saved);

        return toDTO(saved);
    }

    private void storeAttachment(UserSubmission submission, SubmissionType type, MultipartFile file) throws IOException {
        if (type == SubmissionType.IDEA) {
            throw new RuntimeException("Idea submissions do not support attachments");
        }
        if (file.getSize() > MAX_ATTACHMENT_SIZE) {
            throw new RuntimeException("Attachment exceeds 5MB limit");
        }

        String contentType = file.getContentType() != null ? file.getContentType().toLowerCase() : "";
        String originalName = Objects.requireNonNullElse(file.getOriginalFilename(), "attachment");
        String ext = originalName.contains(".")
                ? originalName.substring(originalName.lastIndexOf('.') + 1).toLowerCase()
                : "";

        boolean isBugReport = type == SubmissionType.BUG_REPORT;
        Set<String> allowedMimeTypes = isBugReport ? IMAGE_MIME_TYPES : BROAD_MIME_TYPES;
        Set<String> allowedExtensions = isBugReport ? IMAGE_EXTENSIONS : BROAD_EXTENSIONS;

        if (!allowedMimeTypes.contains(contentType)) {
            throw new RuntimeException("File type not allowed: " + contentType);
        }
        if (!allowedExtensions.contains(ext)) {
            throw new RuntimeException("File extension not allowed: " + ext);
        }

        String storedName = UUID.randomUUID() + "." + ext;
        Path dest = submissionDir(submission.getUser().getId()).resolve(storedName);
        Files.copy(file.getInputStream(), dest);

        submission.setAttachmentPath(storedName);
        submission.setAttachmentMimeType(contentType);
    }

    // ── Email notification (non-fatal) ───────────────────────────────────────

    private void notifyPlatformOwner(UserSubmission submission) {
        if (platformOwnerEmail == null || platformOwnerEmail.isBlank()) {
            log.warn("PLATFORM_OWNER_EMAIL not configured — skipping submission notification email");
            return;
        }
        byte[] attachmentBytes = null;
        String attachmentFilename = null;
        try {
            if (submission.getAttachmentPath() != null) {
                Path file = submissionDir(submission.getUser().getId()).resolve(submission.getAttachmentPath());
                if (Files.exists(file)) {
                    attachmentBytes = Files.readAllBytes(file);
                    attachmentFilename = submission.getAttachmentPath();
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read submission attachment for email: {}", e.getMessage());
        }
        emailService.sendSubmissionNotification(
                platformOwnerEmail,
                submission.getUser().getName(),
                submission.getUser().getEmail(),
                submission.getType().name(),
                submission.getMessage(),
                submission.getClientLogJson(),
                attachmentBytes,
                attachmentFilename,
                submission.getAttachmentMimeType());
    }

    // ── Mapping ─────────────────────────────────────────────────────────────

    private UserSubmissionDTO toDTO(UserSubmission s) {
        UserSubmissionDTO dto = new UserSubmissionDTO();
        dto.setId(s.getId());
        dto.setType(s.getType());
        dto.setMessage(s.getMessage());
        dto.setAttachmentMimeType(s.getAttachmentMimeType());
        dto.setStatus(s.getStatus());
        dto.setRewardGranted(s.isRewardGranted());
        dto.setCreatedAt(s.getCreatedAt());
        return dto;
    }
}
