package com.receipttracker.immigration.controller;

import com.receipttracker.immigration.dto.FormVersionDTO;
import com.receipttracker.immigration.service.FormVersionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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
}
