package com.receipttracker.controller;

import com.receipttracker.dto.PlatformUserDTO;
import com.receipttracker.model.AppFeature;
import com.receipttracker.model.User;
import com.receipttracker.repository.UserRepository;
import com.receipttracker.service.PlatformService;
import com.receipttracker.service.UserFeatureService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/platform/users")
public class PlatformUserController {

    private static final Logger log = LoggerFactory.getLogger(PlatformUserController.class);

    @Autowired private UserRepository userRepo;
    @Autowired private UserFeatureService userFeatureService;
    @Autowired private PlatformService platformService;

    @GetMapping
    public ResponseEntity<?> listUsers() {
        try {
            platformService.requirePlatformAdmin();
            List<PlatformUserDTO> users = userRepo.findAll().stream()
                    .map(u -> new PlatformUserDTO(
                            u.getId(),
                            u.getName(),
                            u.getEmail(),
                            u.getCreatedAt(),
                            Boolean.TRUE.equals(u.getPlatformAdmin()),
                            userFeatureService.getUserFeatures(u.getId())
                    ))
                    .collect(Collectors.toList());
            return ResponseEntity.ok(users);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{userId}/features")
    public ResponseEntity<?> grantFeature(@PathVariable Long userId,
                                           @RequestBody Map<String, String> body) {
        try {
            platformService.requirePlatformAdmin();
            String featureName = body.get("feature");
            AppFeature feature = parseFeature(featureName);
            userFeatureService.grantFeature(userId, feature);
            log.info("Platform: granted {} to user {}", feature, userId);
            return ResponseEntity.ok(Map.of("features", userFeatureService.getUserFeatures(userId)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{userId}/features/{feature}")
    public ResponseEntity<?> revokeFeature(@PathVariable Long userId,
                                            @PathVariable String feature) {
        try {
            platformService.requirePlatformAdmin();
            AppFeature appFeature = parseFeature(feature);
            userFeatureService.revokeFeature(userId, appFeature);
            log.info("Platform: revoked {} from user {}", appFeature, userId);
            return ResponseEntity.ok(Map.of("features", userFeatureService.getUserFeatures(userId)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private AppFeature parseFeature(String name) {
        if (name == null || name.isBlank()) throw new RuntimeException("feature is required");
        try {
            return AppFeature.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Unknown feature: " + name);
        }
    }
}
