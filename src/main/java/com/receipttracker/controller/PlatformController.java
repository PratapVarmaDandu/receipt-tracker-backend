package com.receipttracker.controller;

import com.receipttracker.service.PlatformService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Super-admin endpoints — all operations require user.platformAdmin == true.
 * Role check is enforced inside PlatformService.requirePlatformAdmin().
 */
@RestController
@RequestMapping("/api/platform")
public class PlatformController {

    private static final Logger log = LoggerFactory.getLogger(PlatformController.class);

    @Autowired private PlatformService platformService;

    @GetMapping("/orgs")
    public ResponseEntity<?> listOrgs() {
        try {
            return ResponseEntity.ok(platformService.listAllOrgs());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/stats")
    public ResponseEntity<?> getStats() {
        try {
            return ResponseEntity.ok(platformService.getStats());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/orgs/{slug}/status")
    public ResponseEntity<?> setStatus(@PathVariable String slug,
                                       @RequestBody Map<String, String> body) {
        try {
            String status = body.get("status");
            if (status == null || status.isBlank())
                return ResponseEntity.badRequest().body(Map.of("error", "status is required (ACTIVE or SUSPENDED)"));
            return ResponseEntity.ok(platformService.setOrgStatus(slug, status));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/orgs/{slug}/features")
    public ResponseEntity<?> listFeatures(@PathVariable String slug) {
        try {
            return ResponseEntity.ok(platformService.listOrgFeatures(slug));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/orgs/{slug}/features")
    public ResponseEntity<?> grantFeature(@PathVariable String slug,
                                          @RequestBody Map<String, String> body) {
        try {
            String feature = body.get("feature");
            String expires = body.get("expiresAt");
            LocalDateTime expiresAt = (expires == null || expires.isBlank())
                    ? null : LocalDateTime.parse(expires);
            return ResponseEntity.ok(platformService.grantFeature(slug, feature, expiresAt));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/orgs/{slug}/features/{feature}")
    public ResponseEntity<?> revokeFeature(@PathVariable String slug,
                                           @PathVariable String feature) {
        try {
            return ResponseEntity.ok(platformService.revokeFeature(slug, feature));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/orgs/{slug}/plan")
    public ResponseEntity<?> setPlan(@PathVariable String slug,
                                     @RequestBody Map<String, String> body) {
        try {
            String plan = body.get("plan");
            if (plan == null || plan.isBlank())
                return ResponseEntity.badRequest().body(Map.of("error", "plan is required (FREE or PRO)"));
            return ResponseEntity.ok(platformService.setOrgPlan(slug, plan));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
