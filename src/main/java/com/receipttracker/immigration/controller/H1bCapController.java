package com.receipttracker.immigration.controller;

import com.receipttracker.immigration.dto.CapSeasonSummaryDTO;
import com.receipttracker.immigration.dto.CreateH1bCapRequest;
import com.receipttracker.immigration.dto.H1bCapRegistrationDTO;
import com.receipttracker.immigration.dto.LotteryResultRequest;
import com.receipttracker.immigration.service.H1bCapService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class H1bCapController {

    private static final Logger log = LoggerFactory.getLogger(H1bCapController.class);

    @Autowired private H1bCapService h1bCapService;

    // ── Per-case cap registration ─────────────────────────────────────────────

    @GetMapping("/api/immigration/cases/{id}/h1b-cap")
    public ResponseEntity<?> get(@PathVariable Long id) {
        log.info("GET /api/immigration/cases/{}/h1b-cap", id);
        try {
            return h1bCapService.get(id)
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElse(ResponseEntity.noContent().build());
        } catch (RuntimeException e) {
            return denied(e);
        }
    }

    @PostMapping("/api/immigration/cases/{id}/h1b-cap")
    public ResponseEntity<?> create(@PathVariable Long id, @RequestBody CreateH1bCapRequest req) {
        log.info("POST /api/immigration/cases/{}/h1b-cap year={}", id, req.registrationYear());
        try {
            H1bCapRegistrationDTO dto = h1bCapService.create(id, req);
            return ResponseEntity.status(HttpStatus.CREATED).body(dto);
        } catch (RuntimeException e) {
            return denied(e);
        }
    }

    @PutMapping("/api/immigration/cases/{id}/h1b-cap/lottery-result")
    public ResponseEntity<?> updateLotteryResult(@PathVariable Long id, @RequestBody LotteryResultRequest req) {
        log.info("PUT /api/immigration/cases/{}/h1b-cap/lottery-result selected={}", id, req.selectedInLottery());
        try {
            H1bCapRegistrationDTO dto = h1bCapService.updateLotteryResult(id, req);
            return ResponseEntity.ok(dto);
        } catch (RuntimeException e) {
            return denied(e);
        }
    }

    // ── Per-org cap-season summary ─────────────────────────────────────────────

    @GetMapping("/api/immigration/orgs/{orgId}/cap-season")
    public ResponseEntity<?> getCapSeasonSummary(
            @PathVariable Long orgId,
            @RequestParam(required = false) Integer year) {
        log.info("GET /api/immigration/orgs/{}/cap-season year={}", orgId, year);
        try {
            CapSeasonSummaryDTO dto = h1bCapService.getCapSeasonSummary(orgId, year);
            return ResponseEntity.ok(dto);
        } catch (RuntimeException e) {
            return denied(e);
        }
    }

    private ResponseEntity<?> denied(RuntimeException e) {
        String msg = e.getMessage();
        if (msg != null && msg.startsWith("Access denied")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", msg));
        }
        log.error("!!! h1bCap error: {}", msg);
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }
}
