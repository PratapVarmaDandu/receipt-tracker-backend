package com.receipttracker.immigration.controller;

import com.receipttracker.config.ApiErrors;
import com.receipttracker.immigration.dto.CanonicalProfileDTO;
import com.receipttracker.immigration.dto.UpdateProfileRequest;
import com.receipttracker.immigration.service.CanonicalProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/immigration/profile")
public class ProfileController {

    private static final Logger log = LoggerFactory.getLogger(ProfileController.class);

    @Autowired private CanonicalProfileService profileService;

    /** Returns the current user's canonical profile (creates an empty one on first access). */
    @GetMapping("/me")
    public ResponseEntity<?> getMe() {
        log.info("GET /api/immigration/profile/me");
        try {
            CanonicalProfileDTO dto = profileService.getOrCreateForCurrentUser();
            return ResponseEntity.ok(dto);
        } catch (Exception e) {
            log.error("!!! getMe failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", ApiErrors.safeMessage(e)));
        }
    }

    /** Update the current user's canonical profile. */
    @PutMapping("/me")
    public ResponseEntity<?> updateMe(@RequestBody UpdateProfileRequest req) {
        log.info("PUT /api/immigration/profile/me");
        try {
            CanonicalProfileDTO dto = profileService.updateForCurrentUser(req);
            return ResponseEntity.ok(dto);
        } catch (Exception e) {
            log.error("!!! updateMe failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", ApiErrors.safeMessage(e)));
        }
    }
}
