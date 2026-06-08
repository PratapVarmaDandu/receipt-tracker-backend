package com.receipttracker.controller;

import com.receipttracker.dto.VehicleDTO;
import com.receipttracker.service.VehicleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/vehicles")
public class VehicleController {

    private static final Logger log = LoggerFactory.getLogger(VehicleController.class);

    @Autowired private VehicleService vehicleService;

    // ── CRUD ────────────────────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<?> create(@RequestBody VehicleDTO dto) {
        log.info("POST /api/vehicles make={} model={} year={}", dto.getMake(), dto.getModel(), dto.getModelYear());
        try {
            return ResponseEntity.ok(vehicleService.create(dto));
        } catch (Exception e) {
            log.error("!!! create failed: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<List<VehicleDTO>> list() {
        return ResponseEntity.ok(vehicleService.listMine());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(vehicleService.getById(id));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody VehicleDTO dto) {
        try {
            return ResponseEntity.ok(vehicleService.update(id, dto));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        try {
            vehicleService.delete(id);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Photos ──────────────────────────────────────────────────────────────

    @PostMapping("/{id}/photos")
    public ResponseEntity<?> addPhoto(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        try {
            return ResponseEntity.ok(vehicleService.addPhoto(id, file));
        } catch (Exception e) {
            log.error("!!! addPhoto failed: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}/photos/{filename}")
    public ResponseEntity<?> removePhoto(@PathVariable Long id, @PathVariable String filename) {
        try {
            return ResponseEntity.ok(vehicleService.removePhoto(id, filename));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}/photos/{filename}")
    public ResponseEntity<?> getPhoto(@PathVariable Long id, @PathVariable String filename) {
        try {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                    .contentType(MediaType.IMAGE_JPEG)
                    .body(vehicleService.getPhoto(id, filename));
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ── Schedule ────────────────────────────────────────────────────────────

    @GetMapping("/{id}/schedule")
    public ResponseEntity<?> getSchedule(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(vehicleService.getSchedule(id));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Recalls ─────────────────────────────────────────────────────────────

    @GetMapping("/{id}/recalls")
    public ResponseEntity<?> getRecalls(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(vehicleService.getRecalls(id));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Sharing ──────────────────────────────────────────────────────────────

    @PostMapping("/{id}/access")
    public ResponseEntity<?> inviteAccess(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String email = body.get("email");
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email is required"));
        }
        log.info("POST /api/vehicles/{}/access - email={}", id, email);
        try {
            return ResponseEntity.ok(vehicleService.inviteAccess(id, email));
        } catch (Exception e) {
            log.error("!!! inviteAccess failed vehicleId={}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}/access")
    public ResponseEntity<?> listAccess(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(vehicleService.listAccess(id));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}/access/{accessId}")
    public ResponseEntity<?> revokeAccess(@PathVariable Long id, @PathVariable Long accessId) {
        log.info("DELETE /api/vehicles/{}/access/{}", id, accessId);
        try {
            vehicleService.revokeAccess(id, accessId);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Public — no auth needed; returns vehicle info so the join page can display it. */
    @GetMapping("/access/join/{token}")
    public ResponseEntity<?> getInviteByToken(@PathVariable String token) {
        try {
            return ResponseEntity.ok(vehicleService.getInviteByToken(token));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Auth required — invitee accepts the invite. */
    @PostMapping("/access/join/{token}")
    public ResponseEntity<?> acceptInvite(@PathVariable String token) {
        try {
            return ResponseEntity.ok(vehicleService.acceptInvite(token));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Receipts ─────────────────────────────────────────────────────────────

    @GetMapping("/{id}/receipts")
    public ResponseEntity<?> getLinkedReceipts(@PathVariable Long id) {
        log.trace(">>> GET /api/vehicles/{}/receipts", id);
        try {
            return ResponseEntity.ok(vehicleService.getVehicleReceipts(id));
        } catch (Exception e) {
            log.error("!!! GET /api/vehicles/{}/receipts failed: {}", id, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── PDF Report ──────────────────────────────────────────────────────────

    @GetMapping("/{id}/report")
    public ResponseEntity<?> generateSaleReport(@PathVariable Long id) {
        try {
            byte[] pdf = vehicleService.generateSaleReport(id);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"vehicle-report.pdf\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(pdf);
        } catch (Exception e) {
            log.error("!!! generateSaleReport failed: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
