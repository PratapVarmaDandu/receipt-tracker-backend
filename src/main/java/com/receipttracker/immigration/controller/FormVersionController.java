package com.receipttracker.immigration.controller;

import com.receipttracker.immigration.dto.FormVersionDTO;
import com.receipttracker.immigration.service.FormVersionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
public class FormVersionController {

    @Autowired private FormVersionService formVersionService;

    @GetMapping("/api/immigration/form-versions")
    public ResponseEntity<Map<String, List<FormVersionDTO>>> getAll() {
        return ResponseEntity.ok(formVersionService.getAll());
    }

    @GetMapping("/api/immigration/form-versions/{id}")
    public ResponseEntity<FormVersionDTO> getById(@PathVariable Long id) {
        return ResponseEntity.ok(formVersionService.getById(id));
    }

    @PostMapping("/api/immigration/form-versions/{id}/approve")
    public ResponseEntity<FormVersionDTO> approve(@PathVariable Long id) {
        return ResponseEntity.ok(formVersionService.approve(id));
    }

    @PostMapping(value = "/api/immigration/form-versions/{id}/upload-mapping",
                 consumes = "multipart/form-data")
    public ResponseEntity<FormVersionDTO> uploadMapping(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(formVersionService.uploadMapping(id, file));
    }

    @GetMapping("/api/immigration/form-versions/{id}/mapping-builder")
    public ResponseEntity<?> mappingBuilder(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(formVersionService.getMappingBuilder(id));
        } catch (ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .body(Map.of("error", e.getReason() != null ? e.getReason() : "Failed"));
        }
    }

    @PostMapping("/api/immigration/form-versions/{id}/mapping")
    public ResponseEntity<?> saveMapping(@PathVariable Long id, @RequestBody Map<String, String> pairs) {
        try {
            return ResponseEntity.ok(formVersionService.saveMapping(id, pairs));
        } catch (ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .body(Map.of("error", e.getReason() != null ? e.getReason() : "Failed"));
        }
    }

    @PostMapping(value = "/api/immigration/form-versions",
                 consumes = "multipart/form-data")
    public ResponseEntity<?> createManual(
            @RequestParam("formType") String formType,
            @RequestParam("editionDate") String editionDate,
            @RequestParam("file") MultipartFile file) {
        try {
            return ResponseEntity.ok(formVersionService.createManualVersion(formType, editionDate, file));
        } catch (ResponseStatusException e) {
            // Surface the validation reason (duplicate / not-a-PDF / no fillable fields) to the UI.
            String reason = e.getReason() != null ? e.getReason() : "Upload failed";
            return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", reason));
        }
    }
}
