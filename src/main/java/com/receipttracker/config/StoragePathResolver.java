package com.receipttracker.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves the upload directory at startup based on the server OS, unless
 * overridden by the UPLOAD_DIR environment variable.
 *
 * Windows / macOS → ~/Pictures/Receipts
 * Linux / other   → ~/receipts
 */
@Component
public class StoragePathResolver {

    private static final Logger log = LoggerFactory.getLogger(StoragePathResolver.class);

    @Value("${upload.dir:}")
    private String configuredDir;

    private String resolvedPath;

    @PostConstruct
    public void init() throws IOException {
        resolvedPath = buildPath(configuredDir);
        Path dir = Paths.get(resolvedPath).toAbsolutePath();
        Files.createDirectories(dir);
        resolvedPath = dir.toString();
        log.info("Upload directory resolved to: {} (OS: {})", resolvedPath, System.getProperty("os.name"));
    }

    public String getPath() {
        return resolvedPath;
    }

    public Path asPath() {
        return Paths.get(resolvedPath);
    }

    private static String buildPath(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        String os   = System.getProperty("os.name", "").toLowerCase();
        String home = System.getProperty("user.home", ".");
        if (os.contains("win") || os.contains("mac")) {
            return Paths.get(home, "Pictures", "Receipts").toString();
        }
        return Paths.get(home, "receipts").toString();
    }
}
