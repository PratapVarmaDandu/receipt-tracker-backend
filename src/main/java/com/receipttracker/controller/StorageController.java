package com.receipttracker.controller;

import com.receipttracker.dto.StorageConfigDTO;
import com.receipttracker.dto.StorageUsageDTO;
import com.receipttracker.model.StorageType;
import com.receipttracker.model.User;
import com.receipttracker.repository.ReceiptRepository;
import com.receipttracker.repository.UserRepository;
import com.receipttracker.service.EncryptionService;
import com.receipttracker.service.S3FileStorageService;
import com.receipttracker.service.UserStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/storage")
public class StorageController {

    private static final Logger log = LoggerFactory.getLogger(StorageController.class);

    @Value("${upload.dir:uploads}")
    private String uploadDir;

    @Autowired private UserRepository userRepository;
    @Autowired private ReceiptRepository receiptRepository;
    @Autowired private EncryptionService encryptionService;
    @Autowired private UserStorageService userStorageService;

    @GetMapping("/config")
    public ResponseEntity<StorageConfigDTO> getConfig(Authentication auth) {
        User user = resolveUser(auth);
        return ResponseEntity.ok(toDTO(user));
    }

    @PostMapping("/config")
    public ResponseEntity<StorageConfigDTO> saveConfig(@RequestBody StorageConfigDTO dto, Authentication auth) {
        User user = resolveUser(auth);

        user.setStorageType(dto.getStorageType() != null ? dto.getStorageType() : StorageType.LOCAL);
        user.setLocalStoragePath(nullIfBlank(dto.getLocalStoragePath()));
        user.setS3BucketName(nullIfBlank(dto.getS3BucketName()));
        user.setS3Region(nullIfBlank(dto.getS3Region()));

        if (isNotBlank(dto.getS3AccessKeyId())) {
            user.setS3AccessKeyId(encryptionService.encrypt(dto.getS3AccessKeyId()));
        }
        if (isNotBlank(dto.getS3SecretAccessKey())) {
            user.setS3SecretAccessKey(encryptionService.encrypt(dto.getS3SecretAccessKey()));
        }
        user.setStorageConfigured(true);
        userRepository.save(user);

        log.info("Storage config saved for user={}, type={}", user.getEmail(), user.getStorageType());
        return ResponseEntity.ok(toDTO(user));
    }

    @GetMapping("/usage")
    public ResponseEntity<StorageUsageDTO> getUsage(Authentication auth) {
        User user = resolveUser(auth);
        List<String> filenames = receiptRepository.findByUser(user).stream()
                .map(r -> r.getImageFileName())
                .toList();
        return ResponseEntity.ok(userStorageService.calculateUsage(user, filenames));
    }

    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testConnection(
            @RequestBody StorageConfigDTO dto, Authentication auth) {
        try {
            if (dto.getStorageType() == StorageType.S3) {
                return testS3Connection(dto, auth);
            } else {
                return testLocalPath(dto);
            }
        } catch (Exception e) {
            log.warn("Storage connection test failed: {}", e.getMessage());
            return ResponseEntity.ok(Map.of("success", false, "message",
                    "Connection failed: " + e.getMessage()));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> testS3Connection(
            StorageConfigDTO dto, Authentication auth) throws Exception {
        if (!isNotBlank(dto.getS3BucketName()) || !isNotBlank(dto.getS3Region())) {
            return ResponseEntity.ok(Map.of("success", false,
                    "message", "Bucket name and region are required"));
        }
        if (!isNotBlank(dto.getS3AccessKeyId())) {
            return ResponseEntity.ok(Map.of("success", false,
                    "message", "Access Key ID is required"));
        }

        // Use supplied secret key, or fall back to the saved one
        String secretKey = dto.getS3SecretAccessKey();
        if (!isNotBlank(secretKey)) {
            User user = resolveUser(auth);
            if (user.getS3SecretAccessKey() == null) {
                return ResponseEntity.ok(Map.of("success", false,
                        "message", "Secret Access Key is required"));
            }
            secretKey = encryptionService.decrypt(user.getS3SecretAccessKey());
        }

        try (S3FileStorageService s3 = new S3FileStorageService(
                dto.getS3BucketName(), dto.getS3Region(), dto.getS3AccessKeyId(), secretKey)) {
            s3.testConnection();
        }
        return ResponseEntity.ok(Map.of("success", true,
                "message", "Connected to S3 bucket '" + dto.getS3BucketName() + "' successfully"));
    }

    private ResponseEntity<Map<String, Object>> testLocalPath(StorageConfigDTO dto) throws Exception {
        if (!isNotBlank(dto.getLocalStoragePath())) {
            return ResponseEntity.ok(Map.of("success", true,
                    "message", "Using default server storage"));
        }
        Path path = Paths.get(dto.getLocalStoragePath()).toAbsolutePath();
        Files.createDirectories(path);
        boolean writable = Files.isWritable(path);
        return ResponseEntity.ok(Map.of(
                "success", writable,
                "message", writable
                        ? "Path '" + path + "' is accessible and writable"
                        : "Path exists but is not writable"));
    }

    private StorageConfigDTO toDTO(User user) {
        StorageConfigDTO dto = new StorageConfigDTO();
        dto.setStorageType(user.getStorageType() != null ? user.getStorageType() : StorageType.LOCAL);
        dto.setLocalStoragePath(user.getLocalStoragePath());
        dto.setDefaultStoragePath(Paths.get(uploadDir).toAbsolutePath().toString());
        dto.setS3BucketName(user.getS3BucketName());
        dto.setS3Region(user.getS3Region());
        // Decrypt the access key ID (not highly sensitive) for display
        if (user.getS3AccessKeyId() != null) {
            try {
                dto.setS3AccessKeyId(encryptionService.decrypt(user.getS3AccessKeyId()));
            } catch (Exception ignored) {
                // If decryption fails (e.g. key changed), return null
            }
        }
        // Never return the secret access key — just signal whether it's saved
        dto.setS3SecretKeyConfigured(user.getS3SecretAccessKey() != null);
        dto.setStorageConfigured(user.isStorageConfigured());
        return dto;
    }

    private User resolveUser(Authentication auth) {
        OAuth2User principal = (OAuth2User) auth.getPrincipal();
        String googleId = principal.getAttribute("sub");
        return userRepository.findByGoogleId(googleId)
                .orElseThrow(() -> new RuntimeException("Authenticated user not found in database"));
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
