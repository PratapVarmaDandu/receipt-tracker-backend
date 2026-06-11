package com.receipttracker.controller;

import com.receipttracker.dto.OrgSquareConfigDTO;
import com.receipttracker.dto.OrgSquareConfigRequest;
import com.receipttracker.service.OrganizationService;
import com.receipttracker.service.SquareApiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Per-org Square credential management.
 * All routes require the caller to be a member of the org; role gates enforced in the service.
 */
@RestController
@RequestMapping("/api/organizations/{slug}/square")
public class OrgSquareController {

    private static final Logger log = LoggerFactory.getLogger(OrgSquareController.class);

    @Autowired private OrganizationService orgService;
    @Autowired private SquareApiService squareApiService;

    @GetMapping
    public ResponseEntity<?> getConfig(@PathVariable String slug) {
        try {
            return ResponseEntity.ok(orgService.getSquareConfig(slug));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping
    public ResponseEntity<?> saveConfig(@PathVariable String slug,
                                        @RequestBody OrgSquareConfigRequest req) {
        try {
            return ResponseEntity.ok(orgService.saveSquareConfig(slug, req));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping
    public ResponseEntity<?> clearConfig(@PathVariable String slug) {
        try {
            orgService.clearSquareConfig(slug);
            return ResponseEntity.ok(Map.of("message", "Square configuration cleared"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Validates the saved credentials by hitting the Square Locations API. */
    @PostMapping("/test")
    public ResponseEntity<?> testConnection(@PathVariable String slug) {
        try {
            SquareApiService.SquareCreds creds = orgService.resolveSquareCreds(slug);
            List<java.util.Map<String, Object>> locations = squareApiService.getLocationsForCreds(creds);
            return ResponseEntity.ok(Map.of(
                    "success",       true,
                    "locationCount", locations.size(),
                    "message",       "Connected — " + locations.size() + " active location(s) found"
            ));
        } catch (Exception e) {
            log.warn("Square test connection failed for org={}: {}", slug, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error",   e.getMessage()
            ));
        }
    }
}
