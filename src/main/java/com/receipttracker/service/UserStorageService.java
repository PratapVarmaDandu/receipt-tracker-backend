package com.receipttracker.service;

import com.receipttracker.dto.StorageUsageDTO;
import com.receipttracker.model.StorageType;
import com.receipttracker.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;

@Service
public class UserStorageService {

    private static final Logger log = LoggerFactory.getLogger(UserStorageService.class);

    @Value("${upload.dir:uploads}")
    private String defaultUploadDir;

    @Autowired
    private EncryptionService encryptionService;

    /**
     * After OCR + Vision processing, moves/uploads the temp file to the user's configured storage:
     * <ul>
     *   <li>LOCAL (default path): no-op — file is already in {@code upload.dir}</li>
     *   <li>LOCAL (custom path): moves the file to the configured directory</li>
     *   <li>S3: uploads to the user's bucket and deletes the local temp file</li>
     * </ul>
     * Errors are logged but do NOT propagate — the receipt DB record is still valid even
     * if the file finalization fails (file stays in the default upload dir as a fallback).
     */
    public void finalizeStorage(User user, String filename) {
        StorageType type = user.getStorageType() != null ? user.getStorageType() : StorageType.LOCAL;
        try {
            if (type == StorageType.S3 && user.isStorageConfigured()) {
                uploadToS3(user, filename);
            } else if (type == StorageType.LOCAL
                    && user.getLocalStoragePath() != null
                    && !user.getLocalStoragePath().isBlank()) {
                moveToCustomLocalPath(user.getLocalStoragePath(), filename);
            }
            // else: file stays in default uploadDir — no action needed
        } catch (Exception e) {
            log.warn("Failed to finalize storage for file={}: {} — file remains in default upload dir",
                    filename, e.getMessage());
        }
    }

    /** Returns the temp file written by OcrService (always in the default upload dir). */
    public File getTempFile(String filename) {
        return Paths.get(defaultUploadDir).toAbsolutePath().resolve(filename).toFile();
    }

    private void uploadToS3(User user, String filename) throws IOException {
        String accessKeyId = encryptionService.decrypt(user.getS3AccessKeyId());
        String secretAccessKey = encryptionService.decrypt(user.getS3SecretAccessKey());
        try (S3FileStorageService s3 = new S3FileStorageService(
                user.getS3BucketName(), user.getS3Region(), accessKeyId, secretAccessKey)) {
            File tempFile = getTempFile(filename);
            s3.store(tempFile, filename);
            Files.deleteIfExists(tempFile.toPath());
            log.info("Uploaded {} to s3://{}", filename, user.getS3BucketName());
        }
    }

    /**
     * Deletes the file from wherever it was stored (S3, custom local path, or default upload dir).
     * Non-fatal — errors are logged but not propagated.
     */
    public void deleteFile(User user, String filename) {
        if (filename == null || filename.isBlank()) return;
        StorageType type = user.getStorageType() != null ? user.getStorageType() : StorageType.LOCAL;
        try {
            if (type == StorageType.S3 && user.isStorageConfigured()) {
                deleteFromS3(user, filename);
            } else {
                deleteFromLocal(user, filename);
            }
        } catch (Exception e) {
            log.warn("Failed to delete file={}: {}", filename, e.getMessage());
        }
    }

    private void deleteFromS3(User user, String filename) {
        String accessKeyId    = encryptionService.decrypt(user.getS3AccessKeyId());
        String secretAccessKey = encryptionService.decrypt(user.getS3SecretAccessKey());
        try (S3FileStorageService s3 = new S3FileStorageService(
                user.getS3BucketName(), user.getS3Region(), accessKeyId, secretAccessKey)) {
            s3.deleteObject(filename);
            log.info("Deleted s3://{}/{}", user.getS3BucketName(), filename);
        }
    }

    private void deleteFromLocal(User user, String filename) throws IOException {
        // Try configured custom path first, then fall back to default upload dir
        String customPath = user.getLocalStoragePath();
        if (customPath != null && !customPath.isBlank()) {
            Path custom = Paths.get(customPath).toAbsolutePath().resolve(filename);
            if (Files.deleteIfExists(custom)) {
                log.info("Deleted local file {}", custom);
                return;
            }
        }
        Path fallback = Paths.get(defaultUploadDir).toAbsolutePath().resolve(filename);
        if (Files.deleteIfExists(fallback)) {
            log.info("Deleted local file {}", fallback);
        }
    }

    /**
     * Sums the byte size of every receipt file for the given user.
     * LOCAL: reads file sizes from disk.
     * S3: calls HeadObject for each key (one API call per file).
     */
    public StorageUsageDTO calculateUsage(User user, List<String> filenames) {
        List<String> valid = filenames.stream()
                .filter(f -> f != null && !f.isBlank())
                .toList();

        StorageType type = user.getStorageType() != null ? user.getStorageType() : StorageType.LOCAL;
        long totalBytes = 0;
        int found = 0;
        int missing = 0;
        String location;

        if (type == StorageType.S3 && user.isStorageConfigured()) {
            location = "s3://" + user.getS3BucketName() + " (" + user.getS3Region() + ")";
            String accessKeyId    = encryptionService.decrypt(user.getS3AccessKeyId());
            String secretAccessKey = encryptionService.decrypt(user.getS3SecretAccessKey());
            try (S3FileStorageService s3 = new S3FileStorageService(
                    user.getS3BucketName(), user.getS3Region(), accessKeyId, secretAccessKey)) {
                for (String key : valid) {
                    try {
                        totalBytes += s3.getObjectSize(key);
                        found++;
                    } catch (Exception e) {
                        log.debug("S3 object not found: {}", key);
                        missing++;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to connect to S3 for usage calculation: {}", e.getMessage());
            }
        } else {
            String basePath = (type == StorageType.LOCAL && user.getLocalStoragePath() != null
                    && !user.getLocalStoragePath().isBlank())
                    ? user.getLocalStoragePath()
                    : defaultUploadDir;
            location = Paths.get(basePath).toAbsolutePath().toString();

            for (String filename : valid) {
                // Check configured path first, fall back to default uploadDir
                Path primary  = Paths.get(basePath).toAbsolutePath().resolve(filename);
                Path fallback = Paths.get(defaultUploadDir).toAbsolutePath().resolve(filename);
                try {
                    if (Files.exists(primary)) {
                        totalBytes += Files.size(primary);
                        found++;
                    } else if (!primary.equals(fallback) && Files.exists(fallback)) {
                        totalBytes += Files.size(fallback);
                        found++;
                    } else {
                        missing++;
                    }
                } catch (IOException e) {
                    log.debug("Could not read file size for {}: {}", filename, e.getMessage());
                    missing++;
                }
            }
        }

        StorageUsageDTO dto = new StorageUsageDTO();
        dto.setTotalBytes(totalBytes);
        dto.setFormattedSize(formatBytes(totalBytes));
        dto.setFilesFound(found);
        dto.setFilesNotFound(missing);
        dto.setStorageType(type.name());
        dto.setLocation(location);
        return dto;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1_024)             return bytes + " B";
        if (bytes < 1_048_576)         return String.format("%.1f KB", bytes / 1_024.0);
        if (bytes < 1_073_741_824)     return String.format("%.1f MB", bytes / 1_048_576.0);
        return                                String.format("%.2f GB", bytes / 1_073_741_824.0);
    }

    private void moveToCustomLocalPath(String destPath, String filename) throws IOException {
        Path src = Paths.get(defaultUploadDir).toAbsolutePath().resolve(filename);
        Path destDir = Paths.get(destPath).toAbsolutePath();
        Files.createDirectories(destDir);
        Files.move(src, destDir.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
        log.info("Moved {} to {}", filename, destDir);
    }
}
