package com.receipttracker.controller;

import com.receipttracker.dto.PlatformSquareConfigDTO;
import com.receipttracker.dto.PlatformSquareConfigRequest;
import com.receipttracker.model.Organization;
import com.receipttracker.model.PlatformSquareConfig;
import com.receipttracker.repository.PlatformSquareConfigRepository;
import com.receipttracker.service.EncryptionService;
import com.receipttracker.service.PlatformService;
import com.receipttracker.service.SquareApiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/platform/square-config")
public class PlatformSquareConfigController {

    private static final Logger log = LoggerFactory.getLogger(PlatformSquareConfigController.class);

    @Autowired private PlatformSquareConfigRepository configRepo;
    @Autowired private EncryptionService encryptionService;
    @Autowired private PlatformService platformService;
    @Autowired private SquareApiService squareApiService;

    @GetMapping
    public ResponseEntity<?> getConfig() {
        try {
            platformService.requirePlatformAdmin();
            PlatformSquareConfig cfg = configRepo.findFirstBy().orElse(new PlatformSquareConfig());
            return ResponseEntity.ok(toDTO(cfg));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping
    public ResponseEntity<?> saveConfig(@RequestBody PlatformSquareConfigRequest req) {
        try {
            platformService.requirePlatformAdmin();
            PlatformSquareConfig cfg = configRepo.findFirstBy().orElseGet(PlatformSquareConfig::new);

            if (req.getAccessToken() != null && !req.getAccessToken().isBlank())
                cfg.setAccessTokenEnc(encryptionService.encrypt(req.getAccessToken().trim()));
            if (req.getWebhookSignatureKey() != null && !req.getWebhookSignatureKey().isBlank())
                cfg.setWebhookSignatureKeyEnc(encryptionService.encrypt(req.getWebhookSignatureKey().trim()));

            cfg.setApplicationId(req.getApplicationId());
            cfg.setLocationId(req.getLocationId());
            if (req.getEnvironment() != null)
                cfg.setEnvironment(Organization.SquareEnv.valueOf(req.getEnvironment().toUpperCase()));
            cfg.setPlanIdGarage(req.getPlanIdGarage());
            cfg.setPlanIdVault(req.getPlanIdVault());
            cfg.setPlanIdJobs(req.getPlanIdJobs());
            cfg.setPlanIdSuite(req.getPlanIdSuite());

            configRepo.save(cfg);
            log.info("Platform Square config saved");
            return ResponseEntity.ok(toDTO(cfg));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping
    public ResponseEntity<?> clearConfig() {
        try {
            platformService.requirePlatformAdmin();
            configRepo.findFirstBy().ifPresent(cfg -> {
                cfg.setAccessTokenEnc(null);
                cfg.setApplicationId(null);
                cfg.setLocationId(null);
                cfg.setWebhookSignatureKeyEnc(null);
                cfg.setPlanIdGarage(null);
                cfg.setPlanIdVault(null);
                cfg.setPlanIdJobs(null);
                cfg.setPlanIdSuite(null);
                configRepo.save(cfg);
            });
            log.info("Platform Square config cleared");
            return ResponseEntity.ok(Map.of("message", "Config cleared"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/test")
    public ResponseEntity<?> testConnection() {
        try {
            platformService.requirePlatformAdmin();
            PlatformSquareConfig cfg = configRepo.findFirstBy()
                    .orElseThrow(() -> new RuntimeException("Platform Square is not configured"));
            if (!cfg.isConfigured())
                return ResponseEntity.ok(Map.of("success", false, "message", "Not configured"));

            String token = encryptionService.decrypt(cfg.getAccessTokenEnc());
            String env   = cfg.getEnvironment() != null ? cfg.getEnvironment().name().toLowerCase() : "sandbox";
            SquareApiService.SquareCreds creds = new SquareApiService.SquareCreds(
                    token, env, cfg.getApplicationId(), cfg.getLocationId());

            List<Map<String, Object>> locations = squareApiService.getLocationsForCreds(creds);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "locationCount", locations.size(),
                    "message", "Connected — " + locations.size() + " location(s)"));
        } catch (Exception e) {
            log.warn("Platform Square test failed: {}", e.getMessage());
            return ResponseEntity.ok(Map.of("success", false, "message", e.getMessage()));
        }
    }

    private PlatformSquareConfigDTO toDTO(PlatformSquareConfig cfg) {
        return new PlatformSquareConfigDTO(
                cfg.isConfigured(),
                cfg.getApplicationId(),
                cfg.getLocationId(),
                cfg.getEnvironment() != null ? cfg.getEnvironment().name() : "SANDBOX",
                cfg.getPlanIdGarage(),
                cfg.getPlanIdVault(),
                cfg.getPlanIdJobs(),
                cfg.getPlanIdSuite()
        );
    }
}
