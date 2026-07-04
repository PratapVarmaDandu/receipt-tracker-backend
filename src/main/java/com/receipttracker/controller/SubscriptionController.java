package com.receipttracker.controller;

import com.receipttracker.dto.PlatformSquareConfigDTO;
import com.receipttracker.dto.SubscriptionRequest;
import com.receipttracker.model.PlatformSquareConfig;
import com.receipttracker.repository.PlatformSquareConfigRepository;
import com.receipttracker.service.SubscriptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/subscriptions")
public class SubscriptionController {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionController.class);

    @Autowired private SubscriptionService subscriptionService;
    @Autowired private PlatformSquareConfigRepository configRepo;

    /** Returns public Square SDK fields (no token). Called by the /plans page for all authenticated users. */
    @GetMapping("/square-config")
    public ResponseEntity<?> getPublicSquareConfig() {
        PlatformSquareConfig cfg = configRepo.findFirstBy().orElse(new PlatformSquareConfig());
        return ResponseEntity.ok(new PlatformSquareConfigDTO(
                cfg.isConfigured(),
                cfg.getApplicationId(),
                cfg.getLocationId(),
                cfg.getEnvironment() != null ? cfg.getEnvironment().name() : "SANDBOX",
                cfg.getPlanIdGarage(),
                cfg.getPlanIdVault(),
                cfg.getPlanIdJobs(),
                cfg.getPlanIdSuite()
        ));
    }

    @PostMapping("/square")
    public ResponseEntity<?> purchaseSquareSubscription(@RequestBody SubscriptionRequest req) {
        try {
            log.info(">>> purchaseSquareSubscription planId={}", req.getPlanId());
            String feature = subscriptionService.purchaseSubscription(req.getSourceId(), req.getPlanId());
            log.info("<<< purchaseSquareSubscription granted feature={}", feature);
            return ResponseEntity.ok(Map.of("success", true, "feature", feature));
        } catch (Exception e) {
            log.warn("!!! purchaseSquareSubscription failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Current user's local subscription rows — status/renewal/grace-deadline for the past-due banner. */
    @GetMapping("/mine")
    public ResponseEntity<?> getMine() {
        try {
            return ResponseEntity.ok(subscriptionService.getMySubscriptions());
        } catch (Exception e) {
            log.warn("!!! getMine failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** User-initiated cancellation — schedules cancellation at Square for the end of the current billing period. */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable Long id) {
        try {
            log.info(">>> cancelSubscription id={}", id);
            var dto = subscriptionService.cancelSubscription(id);
            log.info("<<< cancelSubscription id={}", id);
            return ResponseEntity.ok(dto);
        } catch (Exception e) {
            log.warn("!!! cancelSubscription failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
