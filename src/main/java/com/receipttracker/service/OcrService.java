package com.receipttracker.service;

import com.receipttracker.dto.ParsedReceiptData;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Service
public class OcrService {

    private static final Logger log = LoggerFactory.getLogger(OcrService.class);

    @Value("${tesseract.data.path:/opt/homebrew/share/tessdata}")
    private String tessDataPath;

    @Value("${upload.dir:uploads}")
    private String uploadDir;

    public record OcrResult(String savedFilename, String extractedText, ParsedReceiptData parsedData) {
        public OcrResult(String savedFilename, String extractedText) {
            this(savedFilename, extractedText, null);
        }
    }

    public OcrResult processUpload(MultipartFile file) throws IOException {
        // Always use absolute path — Spring 6 / Tomcat requires it for transferTo()
        Path dir = Paths.get(uploadDir).toAbsolutePath().normalize();
        Files.createDirectories(dir);

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

        String text;
        try {
            text = originalName.toLowerCase().endsWith(".pdf")
                    ? extractFromPdf(savedPath.toFile())
                    : extractFromImage(savedPath.toFile());
        } catch (Exception e) {
            log.warn("Text extraction failed for {}: {} — proceeding with empty text", savedName, e.getMessage());
            text = "";
        }

        log.info("OCR extracted {} chars from {}", text.length(), savedName);
        return new OcrResult(savedName, text);
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
}
