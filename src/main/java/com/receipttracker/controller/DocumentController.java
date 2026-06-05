package com.receipttracker.controller;

import com.receipttracker.dto.DocumentDTO;
import com.receipttracker.dto.DocumentNextStepDTO;
import com.receipttracker.service.DocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    @Autowired private DocumentService documentService;

    // ── Upload ──────────────────────────────────────────────────────────────

    @PostMapping("/upload")
    public ResponseEntity<?> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("title") String title,
            @RequestParam("category") String category,
            @RequestParam(value = "subcategory",   required = false) String subcategory,
            @RequestParam(value = "documentYear",  required = false) Integer documentYear,
            @RequestParam(value = "expiryDate",    required = false) String expiryDate,
            @RequestParam(value = "notes",         required = false) String notes) {

        log.info("POST /api/documents/upload file={} category={}", file.getOriginalFilename(), category);
        try {
            DocumentDTO result = documentService.upload(file, title, category, subcategory,
                    documentYear, expiryDate, notes);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("!!! upload failed: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── List ────────────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<List<DocumentDTO>> list(
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(documentService.list(category, page, Math.min(size, 100)));
    }

    @GetMapping("/expiring")
    public ResponseEntity<List<DocumentDTO>> expiring(
            @RequestParam(defaultValue = "90") int days) {
        return ResponseEntity.ok(documentService.getExpiringSoon(days));
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary() {
        return ResponseEntity.ok(documentService.getSummary());
    }

    // ── Get one ─────────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(documentService.getById(id));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Download ────────────────────────────────────────────────────────────

    @GetMapping("/{id}/download")
    public ResponseEntity<?> download(@PathVariable Long id) {
        try {
            DocumentDTO meta = documentService.getById(id);
            Resource resource = documentService.download(id);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + sanitizeFilename(meta.getOriginalFileName()) + "\"")
                    .contentType(mediaType(meta.getContentType()))
                    .body(resource);
        } catch (Exception e) {
            log.error("!!! download failed documentId={}: {}", id, e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Update ──────────────────────────────────────────────────────────────

    @PutMapping("/{id}")
    public ResponseEntity<?> update(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        try {
            DocumentDTO result = documentService.update(
                    id,
                    (String) body.get("title"),
                    (String) body.get("subcategory"),
                    body.get("documentYear") != null ? Integer.valueOf(body.get("documentYear").toString()) : null,
                    (String) body.get("expiryDate"),
                    (String) body.get("notes")
            );
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}/archive")
    public ResponseEntity<?> archive(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(documentService.archive(id));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Delete ──────────────────────────────────────────────────────────────

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        try {
            documentService.delete(id);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Next steps ──────────────────────────────────────────────────────────

    @PostMapping("/{id}/steps")
    public ResponseEntity<?> addStep(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        try {
            DocumentNextStepDTO result = documentService.addNextStep(
                    id,
                    (String) body.get("title"),
                    (String) body.get("description"),
                    (String) body.get("dueDate")
            );
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/steps/{stepId}/complete")
    public ResponseEntity<?> completeStep(@PathVariable Long stepId) {
        try {
            return ResponseEntity.ok(documentService.completeNextStep(stepId));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/steps/{stepId}")
    public ResponseEntity<?> deleteStep(@PathVariable Long stepId) {
        try {
            documentService.deleteNextStep(stepId);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private MediaType mediaType(String contentType) {
        if (contentType == null) return MediaType.APPLICATION_OCTET_STREAM;
        try { return MediaType.parseMediaType(contentType); }
        catch (Exception e) { return MediaType.APPLICATION_OCTET_STREAM; }
    }

    private String sanitizeFilename(String name) {
        if (name == null) return "document";
        return name.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }
}
