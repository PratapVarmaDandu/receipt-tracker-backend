package com.receipttracker.controller;

import com.receipttracker.dto.CreateDocumentShareRequest;
import com.receipttracker.dto.DocumentShareDTO;
import com.receipttracker.model.Document;
import com.receipttracker.repository.DocumentRepository;
import com.receipttracker.service.DocumentShareService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/documents")
public class DocumentShareController {

    private static final Logger log = LoggerFactory.getLogger(DocumentShareController.class);

    @Autowired private DocumentShareService shareService;
    @Autowired private DocumentRepository documentRepo;

    /** Create and email a secure share link — requires auth. */
    @PostMapping("/share")
    public ResponseEntity<?> createShare(@RequestBody CreateDocumentShareRequest req) {
        log.info("POST /api/documents/share recipient={} docs={}", req.getRecipientEmail(), req.getDocumentIds());
        try {
            return ResponseEntity.ok(shareService.createShare(req));
        } catch (Exception e) {
            log.error("!!! createShare failed: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** All shares sent by the current user — requires auth. */
    @GetMapping("/shares/mine")
    public ResponseEntity<List<DocumentShareDTO>> myShares() {
        return ResponseEntity.ok(shareService.myShares());
    }

    /**
     * Public endpoint — no auth required.
     * Returns share metadata + document list for the recipient to view.
     */
    @GetMapping("/shared/{token}")
    public ResponseEntity<?> getByToken(@PathVariable String token) {
        try {
            return ResponseEntity.ok(shareService.getByToken(token));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Public endpoint — download a specific document via share token.
     * No auth required; validated against the token's document list.
     */
    @GetMapping("/shared/{token}/download/{documentId}")
    public ResponseEntity<?> downloadViaToken(
            @PathVariable String token,
            @PathVariable Long documentId) {
        try {
            Resource resource = shareService.downloadViaToken(token, documentId);
            Document doc = documentRepo.findById(documentId)
                    .orElseThrow(() -> new RuntimeException("Document not found"));

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + sanitize(doc.getOriginalFileName()) + "\"")
                    .contentType(mediaType(doc.getContentType()))
                    .body(resource);
        } catch (Exception e) {
            log.error("!!! downloadViaToken failed: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private MediaType mediaType(String ct) {
        if (ct == null) return MediaType.APPLICATION_OCTET_STREAM;
        try { return MediaType.parseMediaType(ct); }
        catch (Exception e) { return MediaType.APPLICATION_OCTET_STREAM; }
    }

    private String sanitize(String name) {
        if (name == null) return "document";
        return name.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }
}
