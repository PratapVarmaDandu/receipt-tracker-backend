package com.receipttracker.immigration.controller;

import com.receipttracker.immigration.dto.CanonicalProfileDTO;
import com.receipttracker.immigration.dto.CreateCaseRequest;
import com.receipttracker.immigration.dto.ImmigrationCaseDTO;
import com.receipttracker.immigration.service.CanonicalProfileService;
import com.receipttracker.immigration.service.CaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/immigration/cases")
public class CaseController {

    private static final Logger log = LoggerFactory.getLogger(CaseController.class);

    @Autowired private CaseService caseService;
    @Autowired private CanonicalProfileService canonicalProfileService;

    /** Create a new immigration case. Caller must already be registered as a beneficiary. */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateCaseRequest req) {
        log.info("POST /api/immigration/cases caseType={}", req.caseType());
        try {
            ImmigrationCaseDTO dto = caseService.create(req);
            return ResponseEntity.status(HttpStatus.CREATED).body(dto);
        } catch (Exception e) {
            log.error("!!! create case failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** List all immigration cases accessible to the current user (via active Grants). */
    @GetMapping
    public ResponseEntity<?> list() {
        log.info("GET /api/immigration/cases");
        try {
            List<ImmigrationCaseDTO> cases = caseService.listAccessible();
            return ResponseEntity.ok(cases);
        } catch (Exception e) {
            log.error("!!! list cases failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Get a single case by ID. Returns 403 if the caller has no READ_CASE grant. */
    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        log.info("GET /api/immigration/cases/{}", id);
        try {
            ImmigrationCaseDTO dto = caseService.getById(id);
            return ResponseEntity.ok(dto);
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            if (msg != null && msg.startsWith("Access denied")) {
                log.warn("!!! 403 {}", msg);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", msg));
            }
            log.error("!!! getById {} failed: {}", id, msg);
            return ResponseEntity.badRequest().body(Map.of("error", msg));
        }
    }

    /** List cases linked to an ImmOrg (employer or law firm). Caller must be a member of that org. */
    @GetMapping("/by-org/{orgId}")
    public ResponseEntity<?> listByOrg(@PathVariable Long orgId) {
        log.info("GET /api/immigration/cases/by-org/{}", orgId);
        try {
            return ResponseEntity.ok(caseService.listByOrg(orgId));
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            if (msg != null && msg.startsWith("Access denied")) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", msg));
            }
            log.error("!!! listByOrg {} failed: {}", orgId, msg);
            return ResponseEntity.badRequest().body(Map.of("error", msg));
        }
    }

    /** Transition case status. Caller must have WRITE_CASE grant. */
    @PutMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String newStatus = body.get("status");
        log.info("PUT /api/immigration/cases/{}/status newStatus={}", id, newStatus);
        try {
            ImmigrationCaseDTO dto = caseService.updateStatus(id, newStatus);
            return ResponseEntity.ok(dto);
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            if (msg != null && msg.startsWith("Access denied")) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", msg));
            }
            log.error("!!! updateStatus failed: {}", msg);
            return ResponseEntity.badRequest().body(Map.of("error", msg));
        }
    }

    /** Returns the beneficiary's canonical profile. Requires READ_CASE grant (attorney / HR admin). */
    @GetMapping("/{id}/beneficiary/profile")
    public ResponseEntity<?> getBeneficiaryProfile(@PathVariable Long id) {
        log.info("GET /api/immigration/cases/{}/beneficiary/profile", id);
        try {
            CanonicalProfileDTO dto = canonicalProfileService.getForCase(id);
            return ResponseEntity.ok(dto);
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            if (msg != null && msg.startsWith("Access denied")) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", msg));
            }
            return ResponseEntity.badRequest().body(Map.of("error", msg));
        }
    }

    /** Proxy-download a profile document; requires READ_CASE grant. */
    @GetMapping("/{caseId}/profile/documents/{docId}/download")
    public ResponseEntity<?> downloadProfileDoc(@PathVariable Long caseId, @PathVariable Long docId) {
        log.info("GET /api/immigration/cases/{}/profile/documents/{}/download", caseId, docId);
        try {
            Resource resource = canonicalProfileService.downloadProfileDocument(caseId, docId);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"document\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(resource);
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            if (msg != null && msg.startsWith("Access denied")) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", msg));
            }
            return ResponseEntity.badRequest().body(Map.of("error", msg));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Public — no auth required.
     * Returns minimal case info for the beneficiary invite page (employer name, case type, law firm).
     */
    @GetMapping("/join/{token}")
    public ResponseEntity<?> getByInviteToken(@PathVariable String token) {
        log.info("GET /api/immigration/cases/join/{}", token);
        try {
            return ResponseEntity.ok(caseService.getByInviteToken(token));
        } catch (RuntimeException e) {
            log.warn("!!! getByInviteToken failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Auth required — beneficiary accepts their invite.
     * Validates that the logged-in user's email matches the invite email.
     */
    @PostMapping("/join/{token}")
    public ResponseEntity<?> acceptInvite(@PathVariable String token) {
        log.info("POST /api/immigration/cases/join/{}", token);
        try {
            ImmigrationCaseDTO dto = caseService.acceptInvite(token);
            return ResponseEntity.ok(dto);
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            log.warn("!!! acceptInvite failed: {}", msg);
            if (msg != null && msg.startsWith("This invite is not for your account")) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", msg));
            }
            return ResponseEntity.badRequest().body(Map.of("error", msg));
        }
    }
}
