package com.receipttracker.service;

import com.receipttracker.config.StoragePathResolver;
import com.receipttracker.dto.ParsedReceiptData;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class OcrService {

    private static final Logger log = LoggerFactory.getLogger(OcrService.class);

    @Value("${tesseract.data.path:/opt/homebrew/share/tessdata}")
    private String tessDataPath;

    @Autowired
    private StoragePathResolver storagePathResolver;

    public record OcrResult(String savedFilename, String extractedText, ParsedReceiptData parsedData) {
        public OcrResult(String savedFilename, String extractedText) {
            this(savedFilename, extractedText, null);
        }
    }

    public OcrResult processUpload(MultipartFile file) throws IOException {
        Path dir = storagePathResolver.asPath();

        String originalName = file.getOriginalFilename() != null
                ? file.getOriginalFilename().replaceAll("[^a-zA-Z0-9._-]", "_")
                : "receipt.pdf";

        String savedName = UUID.randomUUID() + "_" + originalName;
        Path savedPath = dir.resolve(savedName);

        // Use Files.copy from stream — avoids transferTo() absolute-path issues
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, savedPath, StandardCopyOption.REPLACE_EXISTING);
        }

        log.info("Saved upload to {}", savedPath);

        // Convert HEIC/HEIF to JPEG before OCR — Tesseract and Claude cannot read HEIC natively
        File fileForProcessing = savedPath.toFile();
        String processedName = savedName;
        if (isHeic(originalName)) {
            try {
                File converted = convertHeicToJpeg(savedPath.toFile());
                fileForProcessing = converted;
                processedName = converted.getName();
                log.info("HEIC converted to JPEG: {}", processedName);
            } catch (IOException e) {
                log.warn("HEIC conversion failed — OCR/Vision AI will likely fail: {}", e.getMessage());
            }
        }

        String text;
        try {
            text = originalName.toLowerCase().endsWith(".pdf")
                    ? extractFromPdf(savedPath.toFile())
                    : extractFromImage(fileForProcessing);
        } catch (Exception e) {
            log.warn("Text extraction failed for {}: {} — proceeding with empty text", processedName, e.getMessage());
            text = "";
        }

        log.info("OCR extracted {} chars from {}", text.length(), processedName);
        return new OcrResult(processedName, text);
    }

    private String extractFromPdf(File file) throws IOException {
        try (PDDocument doc = Loader.loadPDF(file)) {
            String text = new PDFTextStripper().getText(doc);
            log.info("PDFBox extracted {} chars", text.length());
            return text;
        }
    }

    private String extractFromImage(File file) {
        try {
            Tesseract tess = new Tesseract();
            tess.setDatapath(tessDataPath);
            tess.setLanguage("eng");
            tess.setPageSegMode(1);
            tess.setOcrEngineMode(1);
            return tess.doOCR(file);
        } catch (TesseractException | UnsatisfiedLinkError e) {
            log.warn("Tesseract unavailable: {}. Install: brew install tesseract", e.getMessage());
            return "";
        }
    }

    // ── HEIC conversion ───────────────────────────────────────────────────────

    private static boolean isHeic(String filename) {
        String lower = filename.toLowerCase();
        return lower.endsWith(".heic") || lower.endsWith(".heif");
    }

    /**
     * Converts a HEIC/HEIF file to JPEG by trying available system converters in order:
     * 1. sips        — built-in on macOS
     * 2. heif-convert — provided by libheif-tools/libheif-examples on Linux
     * 3. convert     — ImageMagick (any platform)
     */
    private File convertHeicToJpeg(File heicFile) throws IOException {
        String heicPath = heicFile.getAbsolutePath();
        String jpegPath = heicPath.replaceAll("(?i)\\.(heic|heif)$", ".jpg");
        File jpegFile = new File(jpegPath);

        if (runConverter(List.of("sips", "-s", "format", "jpeg", heicPath, "--out", jpegPath), jpegFile)) {
            return jpegFile;
        }
        if (runConverter(List.of("heif-convert", heicPath, jpegPath), jpegFile)) {
            return jpegFile;
        }
        if (runConverter(List.of("convert", heicPath, jpegPath), jpegFile)) {
            return jpegFile;
        }
        throw new IOException(
            "No HEIC converter found. Install one of: sips (macOS), libheif-tools (Linux), or imagemagick");
    }

    private boolean runConverter(List<String> cmd, File expectedOutput) {
        try {
            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();
            // Drain stdout/stderr so the process doesn't block on a full buffer
            p.getInputStream().transferTo(OutputStream.nullOutputStream());
            boolean finished = p.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                log.warn("HEIC converter '{}' timed out after 60s — killed", cmd.get(0));
                return false;
            }
            if (p.exitValue() == 0 && expectedOutput.exists() && expectedOutput.length() > 0) {
                log.debug("HEIC converter '{}' succeeded", cmd.get(0));
                return true;
            }
        } catch (Exception e) {
            log.debug("HEIC converter '{}' unavailable: {}", cmd.get(0), e.getMessage());
        }
        return false;
    }
}
