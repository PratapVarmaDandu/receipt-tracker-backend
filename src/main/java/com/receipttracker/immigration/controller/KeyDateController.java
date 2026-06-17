package com.receipttracker.immigration.controller;

import com.receipttracker.immigration.dto.KeyDateDTO;
import com.receipttracker.immigration.service.KeyDateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/immigration/cases/{caseId}/key-dates")
public class KeyDateController {

    private static final Logger log = LoggerFactory.getLogger(KeyDateController.class);

    @Autowired private KeyDateService keyDateService;

    @GetMapping
    public ResponseEntity<?> list(@PathVariable Long caseId) {
        log.info("GET /api/immigration/cases/{}/key-dates", caseId);
        try { return ResponseEntity.ok(keyDateService.listForCase(caseId)); }
        catch (RuntimeException e) { return handleError(e); }
    }

    @PostMapping("/sync")
    public ResponseEntity<?> sync(@PathVariable Long caseId) {
        log.info("POST /api/immigration/cases/{}/key-dates/sync", caseId);
        try {
            List<KeyDateDTO> dates = keyDateService.syncFromProfile(caseId);
            return ResponseEntity.ok(dates);
        } catch (RuntimeException e) { return handleError(e); }
    }

    @PostMapping
    public ResponseEntity<?> add(@PathVariable Long caseId, @RequestBody Map<String, String> body) {
        log.info("POST /api/immigration/cases/{}/key-dates", caseId);
        try {
            KeyDateDTO dto = keyDateService.addManual(
                    caseId, body.get("dateType"), body.get("date"),
                    body.get("label"), body.get("notes"));
            return ResponseEntity.status(HttpStatus.CREATED).body(dto);
        } catch (RuntimeException e) { return handleError(e); }
    }

    @DeleteMapping("/{keyDateId}")
    public ResponseEntity<?> delete(@PathVariable Long caseId, @PathVariable Long keyDateId) {
        log.info("DELETE /api/immigration/cases/{}/key-dates/{}", caseId, keyDateId);
        try { keyDateService.delete(caseId, keyDateId); return ResponseEntity.noContent().build(); }
        catch (RuntimeException e) { return handleError(e); }
    }

    private ResponseEntity<?> handleError(RuntimeException e) {
        String msg = e.getMessage();
        if (msg != null && msg.startsWith("Access denied"))
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", msg));
        log.error("!!! KeyDateController error: {}", msg);
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }
}
