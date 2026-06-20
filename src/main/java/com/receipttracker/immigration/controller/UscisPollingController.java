package com.receipttracker.immigration.controller;

import com.receipttracker.immigration.dto.UscisStatusDTO;
import com.receipttracker.immigration.service.UscisPollingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class UscisPollingController {

    private static final Logger log = LoggerFactory.getLogger(UscisPollingController.class);

    @Autowired private UscisPollingService uscisService;

    @GetMapping("/api/immigration/cases/{caseId}/uscis-status-history")
    public ResponseEntity<?> getHistory(@PathVariable Long caseId) {
        log.info("GET /api/immigration/cases/{}/uscis-status-history", caseId);
        try {
            List<UscisStatusDTO> history = uscisService.getHistory(caseId);
            return ResponseEntity.ok(history);
        } catch (RuntimeException e) {
            return denied(e);
        }
    }

    @PostMapping("/api/immigration/cases/{caseId}/uscis-check-now")
    public ResponseEntity<?> checkNow(@PathVariable Long caseId) {
        log.info("POST /api/immigration/cases/{}/uscis-check-now", caseId);
        try {
            UscisStatusDTO result = uscisService.checkNow(caseId);
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return denied(e);
        }
    }

    private ResponseEntity<?> denied(RuntimeException e) {
        String msg = e.getMessage();
        if (msg != null && msg.startsWith("Access denied")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", msg));
        }
        log.error("!!! uscis error: {}", msg);
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }
}
