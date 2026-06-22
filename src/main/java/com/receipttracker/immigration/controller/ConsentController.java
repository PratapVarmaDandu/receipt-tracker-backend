package com.receipttracker.immigration.controller;

import com.receipttracker.config.ApiErrors;
import com.receipttracker.immigration.service.ConsentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/immigration/cases/{caseId}/consent")
public class ConsentController {

    private static final Logger log = LoggerFactory.getLogger(ConsentController.class);

    @Autowired private ConsentService consentService;

    @GetMapping
    public ResponseEntity<?> list(@PathVariable Long caseId) {
        log.info("GET /api/immigration/cases/{}/consent", caseId);
        try { return ResponseEntity.ok(consentService.listForCase(caseId)); }
        catch (RuntimeException e) { return handleError(e); }
    }

    @PostMapping("/grant")
    public ResponseEntity<?> grant(@PathVariable Long caseId, @RequestBody ConsentRequest req) {
        log.info("POST /api/immigration/cases/{}/consent/grant", caseId);
        try { return ResponseEntity.status(HttpStatus.CREATED)
                .body(consentService.grantConsent(caseId, req.relationship(), req.notes())); }
        catch (RuntimeException e) { return handleError(e); }
    }

    @PostMapping("/revoke")
    public ResponseEntity<?> revoke(@PathVariable Long caseId, @RequestBody ConsentRequest req) {
        log.info("POST /api/immigration/cases/{}/consent/revoke", caseId);
        try { return ResponseEntity.ok(consentService.revokeConsent(caseId, req.relationship(), req.notes())); }
        catch (RuntimeException e) { return handleError(e); }
    }

    private ResponseEntity<?> handleError(RuntimeException e) {
        String msg = ApiErrors.safeMessage(e);
        if (msg != null && msg.startsWith("Access denied"))
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", msg));
        if (msg != null && msg.startsWith("Only the beneficiary"))
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", msg));
        log.error("!!! ConsentController error: {}", msg);
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    record ConsentRequest(String relationship, String notes) {}
}
