package com.receipttracker.controller;

import com.receipttracker.dto.*;
import com.receipttracker.service.ExpenseShareService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class ExpenseShareController {

    private static final Logger log = LoggerFactory.getLogger(ExpenseShareController.class);

    @Autowired
    private ExpenseShareService shareService;

    @PostMapping("/api/receipts/{receiptId}/shares")
    public ResponseEntity<?> createShares(@PathVariable Long receiptId,
                                          @RequestBody CreateShareRequest req) {
        log.trace(">>> POST /api/receipts/{}/shares", receiptId);
        long startTime = System.currentTimeMillis();
        try {
            List<ExpenseShareDTO> result = shareService.createShares(receiptId, req);
            log.debug("<<< POST /api/receipts/{}/shares created={} in {}ms",
                    receiptId, result.size(), System.currentTimeMillis() - startTime);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("!!! POST /api/receipts/{}/shares failed: {}", receiptId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/api/receipts/{receiptId}/shares")
    public ResponseEntity<?> getSharesForReceipt(@PathVariable Long receiptId) {
        log.trace(">>> GET /api/receipts/{}/shares", receiptId);
        long startTime = System.currentTimeMillis();
        try {
            List<ExpenseShareDTO> result = shareService.getSharesForReceipt(receiptId);
            log.debug("<<< GET /api/receipts/{}/shares count={} in {}ms",
                    receiptId, result.size(), System.currentTimeMillis() - startTime);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("!!! GET /api/receipts/{}/shares failed: {}", receiptId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/api/shares/token/{token}")
    public ResponseEntity<?> getShareByToken(@PathVariable String token) {
        log.trace(">>> GET /api/shares/token/{}", token);
        long startTime = System.currentTimeMillis();
        try {
            ShareViewDTO result = shareService.getShareByToken(token);
            log.debug("<<< GET /api/shares/token/{} in {}ms", token, System.currentTimeMillis() - startTime);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.warn("!!! GET /api/shares/token/[token] failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/api/shares/token/{token}/action")
    public ResponseEntity<?> inviteeAction(@PathVariable String token,
                                           @RequestBody InviteeActionRequest req) {
        log.trace(">>> POST /api/shares/token/[token]/action action={}", req.getAction());
        long startTime = System.currentTimeMillis();
        try {
            ExpenseShareDTO result = shareService.processInviteeAction(token, req);
            log.debug("<<< POST /api/shares/token/[token]/action in {}ms", System.currentTimeMillis() - startTime);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.warn("!!! POST /api/shares/token/[token]/action failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/api/shares/{shareId}/owner-action")
    public ResponseEntity<?> ownerAction(@PathVariable Long shareId,
                                         @RequestBody OwnerActionRequest req) {
        log.trace(">>> PUT /api/shares/{}/owner-action action={}", shareId, req.getAction());
        long startTime = System.currentTimeMillis();
        try {
            ExpenseShareDTO result = shareService.processOwnerAction(shareId, req);
            log.debug("<<< PUT /api/shares/{}/owner-action in {}ms", shareId, System.currentTimeMillis() - startTime);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("!!! PUT /api/shares/{}/owner-action failed: {}", shareId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/api/shares/mine")
    public ResponseEntity<?> getMyShares() {
        log.trace(">>> GET /api/shares/mine");
        long startTime = System.currentTimeMillis();
        try {
            List<ExpenseShareDTO> result = shareService.getMyShares();
            log.debug("<<< GET /api/shares/mine count={} in {}ms", result.size(), System.currentTimeMillis() - startTime);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("!!! GET /api/shares/mine failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
