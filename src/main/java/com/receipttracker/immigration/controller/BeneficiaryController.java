package com.receipttracker.immigration.controller;

import com.receipttracker.immigration.dto.BeneficiaryDTO;
import com.receipttracker.immigration.service.BeneficiaryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/immigration/beneficiary")
public class BeneficiaryController {

    private static final Logger log = LoggerFactory.getLogger(BeneficiaryController.class);

    @Autowired private BeneficiaryService beneficiaryService;

    /**
     * Returns the beneficiary record for the current user, creating it on first access.
     * This is the "register as beneficiary" step — safe to call multiple times (idempotent).
     */
    @PostMapping("/init")
    public ResponseEntity<?> initBeneficiary() {
        log.info("POST /api/immigration/beneficiary/init");
        try {
            BeneficiaryDTO dto = beneficiaryService.getOrCreateForCurrentUser();
            return ResponseEntity.ok(dto);
        } catch (Exception e) {
            log.error("!!! initBeneficiary failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Returns the beneficiary record for the current user (must already exist).
     */
    @GetMapping("/me")
    public ResponseEntity<?> getCurrent() {
        log.info("GET /api/immigration/beneficiary/me");
        try {
            BeneficiaryDTO dto = beneficiaryService.getCurrent();
            return ResponseEntity.ok(dto);
        } catch (Exception e) {
            log.error("!!! getCurrent failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Returns whether the current user has a beneficiary profile.
     */
    @GetMapping("/status")
    public ResponseEntity<?> status() {
        log.info("GET /api/immigration/beneficiary/status");
        boolean isBeneficiary = beneficiaryService.currentUserIsBeneficiary();
        return ResponseEntity.ok(Map.of("isBeneficiary", isBeneficiary));
    }
}
