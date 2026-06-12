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
}
