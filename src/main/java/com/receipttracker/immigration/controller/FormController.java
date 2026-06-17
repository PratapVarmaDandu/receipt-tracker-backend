package com.receipttracker.immigration.controller;

import com.receipttracker.immigration.dto.FormInstanceDTO;
import com.receipttracker.immigration.service.FormInstanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/immigration/cases/{caseId}/forms")
public class FormController {

    private static final Logger log = LoggerFactory.getLogger(FormController.class);

    @Autowired private FormInstanceService formService;

    @GetMapping
    public ResponseEntity<?> list(@PathVariable Long caseId) {
        log.info("GET /api/immigration/cases/{}/forms", caseId);
        try {
            List<FormInstanceDTO> forms = formService.listForCase(caseId);
            return ResponseEntity.ok(forms);
        } catch (RuntimeException e) {
            return handleError(e);
        }
    }

    @GetMapping("/{formId}")
    public ResponseEntity<?> getById(@PathVariable Long caseId, @PathVariable Long formId) {
        log.info("GET /api/immigration/cases/{}/forms/{}", caseId, formId);
        try {
            return ResponseEntity.ok(formService.getById(caseId, formId));
        } catch (RuntimeException e) {
            return handleError(e);
        }
    }

    /** Generate / refresh all form instances from the beneficiary's canonical profile. */
    @PostMapping("/generate")
    public ResponseEntity<?> generate(@PathVariable Long caseId) {
        log.info("POST /api/immigration/cases/{}/forms/generate", caseId);
        try {
            List<FormInstanceDTO> forms = formService.generateFromProfile(caseId);
            return ResponseEntity.status(HttpStatus.CREATED).body(forms);
        } catch (RuntimeException e) {
            return handleError(e);
        }
    }

    @PutMapping("/{formId}/status")
    public ResponseEntity<?> updateStatus(
            @PathVariable Long caseId,
            @PathVariable Long formId,
            @RequestBody Map<String, String> body) {
        String status = body.get("status");
        log.info("PUT /api/immigration/cases/{}/forms/{}/status status={}", caseId, formId, status);
        try {
            return ResponseEntity.ok(formService.updateStatus(caseId, formId, status));
        } catch (RuntimeException e) {
            return handleError(e);
        }
    }

    private ResponseEntity<?> handleError(RuntimeException e) {
        String msg = e.getMessage();
        if (msg != null && msg.startsWith("Access denied")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", msg));
        }
        log.error("!!! FormController error: {}", msg);
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }
}
