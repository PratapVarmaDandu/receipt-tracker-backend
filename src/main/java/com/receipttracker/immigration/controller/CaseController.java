package com.receipttracker.immigration.controller;

import com.receipttracker.config.ApiErrors;
import com.receipttracker.immigration.dto.CanonicalProfileDTO;
import com.receipttracker.immigration.dto.CloneCaseRequest;
import com.receipttracker.immigration.dto.ConflictCheckRequest;
import com.receipttracker.immigration.dto.ConflictSummaryDTO;
import com.receipttracker.immigration.dto.CreateCaseRequest;
import com.receipttracker.immigration.dto.FamilyBundleDTO;
import com.receipttracker.immigration.dto.ImmigrationCaseDTO;
import com.receipttracker.immigration.dto.StatusHistoryDTO;
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
        } catch (RuntimeException e) {
            log.error("!!! create case failed: {}", e.getMessage());
            String msg = ApiErrors.safeMessage(e);
            if (msg != null && msg.startsWith("Access denied")) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", msg));
            }
            return ResponseEntity.badRequest().body(Map.of("error", msg != null ? msg : "Failed to create case"));
        } catch (Exception e) {
            log.error("!!! create case unexpected error", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "An unexpected error occurred. Please try again."));
        }
    }

    /** List all immigration cases accessible to the current user (via active Grants). */
    @GetMapping
    public ResponseEntity<?> list() {
        log.info("GET /api/immigration/cases");
        try {
            List<ImmigrationCaseDTO> cases = caseService.listAccessible();
            return ResponseEntity.ok(cases);
        } catch (RuntimeException e) {
            log.error("!!! list cases failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", ApiErrors.safeMessage(e)));
        } catch (Exception e) {
            log.error("!!! list cases unexpected error", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "An unexpected error occurred. Please try again."));
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
            String msg = ApiErrors.safeMessage(e);
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
            String msg = ApiErrors.safeMessage(e);
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
            String msg = ApiErrors.safeMessage(e);
            if (msg != null && msg.startsWith("Access denied")) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", msg));
            }
            log.error("!!! updateStatus failed: {}", msg);
            return ResponseEntity.badRequest().body(Map.of("error", msg));
        }
    }

    /** Set or update the USCIS receipt number. Attorney + WRITE_CASE grant required. */
    @PutMapping("/{id}/receipt-number")
    public ResponseEntity<?> updateReceiptNumber(@PathVariable Long id, @RequestBody Map<String, String> body) {
        log.info("PUT /api/immigration/cases/{}/receipt-number", id);
        try {
            ImmigrationCaseDTO dto = caseService.updateReceiptNumber(id, body.get("receiptNumber"));
            return ResponseEntity.ok(dto);
        } catch (RuntimeException e) {
            String msg = ApiErrors.safeMessage(e);
            if (msg != null && msg.startsWith("Access denied")) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", msg));
            }
            log.error("!!! updateReceiptNumber failed: {}", msg);
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
            String msg = ApiErrors.safeMessage(e);
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
            String msg = ApiErrors.safeMessage(e);
            if (msg != null && msg.startsWith("Access denied")) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", msg));
            }
            return ResponseEntity.badRequest().body(Map.of("error", msg));
        } catch (Exception e) {
            log.error("!!! downloadProfileDoc unexpected error", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "An unexpected error occurred. Please try again."));
        }
    }

    /** Assign a paralegal (ImmOrgMember) to the case. Caller must have WRITE_CASE grant. */
    @PostMapping("/{id}/assign-paralegal")
    public ResponseEntity<?> assignParalegal(
            @PathVariable Long id,
            @RequestBody Map<String, Long> body) {
        Long memberId = body.get("memberId");  // null = remove paralegal
        log.info("POST /api/immigration/cases/{}/assign-paralegal memberId={}", id, memberId);
        try {
            ImmigrationCaseDTO dto = caseService.assignParalegal(id, memberId);
            return ResponseEntity.ok(dto);
        } catch (RuntimeException e) {
            String msg = ApiErrors.safeMessage(e);
            if (msg != null && msg.startsWith("Access denied")) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", msg));
            }
            log.error("!!! assignParalegal failed: {}", msg);
            return ResponseEntity.badRequest().body(Map.of("error", msg));
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
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ApiErrors.safeMessage(e)));
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
            String msg = ApiErrors.safeMessage(e);
            log.warn("!!! acceptInvite failed: {}", msg);
            if (msg != null && msg.startsWith("This invite is not for your account")) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", msg));
            }
            return ResponseEntity.badRequest().body(Map.of("error", msg));
        }
    }

    /**
     * Auth required — beneficiary accepts an invite from the in-app banner (by case id, no token).
     * Used when the beneficiary is already signed in and never clicked the email link.
     */
    @PostMapping("/{id}/accept-invite")
    public ResponseEntity<?> acceptInviteByCaseId(@PathVariable Long id) {
        log.info("POST /api/immigration/cases/{}/accept-invite", id);
        try {
            ImmigrationCaseDTO dto = caseService.acceptInviteByCaseId(id);
            return ResponseEntity.ok(dto);
        } catch (RuntimeException e) {
            String msg = ApiErrors.safeMessage(e);
            log.warn("!!! acceptInviteByCaseId failed: {}", msg);
            if (msg != null && (msg.startsWith("This invite is not for your account")
                    || msg.startsWith("Access denied"))) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", msg));
            }
            return ResponseEntity.badRequest().body(Map.of("error", msg));
        }
    }

    /** Conflict-of-interest check before opening a new case (ATTORNEY/OWNER in a law firm). */
    @PostMapping("/conflict-check")
    public ResponseEntity<?> conflictCheck(@RequestBody ConflictCheckRequest req) {
        log.info("POST /api/immigration/cases/conflict-check");
        try {
            List<ConflictSummaryDTO> conflicts = caseService.checkConflict(req);
            return ResponseEntity.ok(conflicts);
        } catch (RuntimeException e) {
            String msg = ApiErrors.safeMessage(e);
            if (msg != null && msg.startsWith("Access denied")) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", msg));
            }
            log.error("!!! conflictCheck failed: {}", msg);
            return ResponseEntity.badRequest().body(Map.of("error", msg));
        }
    }

    /** Returns the primary case + all dependent cases (parentCaseId = id). Requires READ_CASE grant. */
    @GetMapping("/{id}/family")
    public ResponseEntity<?> getFamily(@PathVariable Long id) {
        log.info("GET /api/immigration/cases/{}/family", id);
        try {
            FamilyBundleDTO bundle = caseService.getFamily(id);
            return ResponseEntity.ok(bundle);
        } catch (RuntimeException e) {
            String msg = ApiErrors.safeMessage(e);
            if (msg != null && msg.startsWith("Access denied")) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", msg));
            }
            log.error("!!! getFamily {} failed: {}", id, msg);
            return ResponseEntity.badRequest().body(Map.of("error", msg));
        }
    }

    /** Clone an existing case as a new case type (attorney only). Requires WRITE_CASE grant. */
    @PostMapping("/{id}/clone")
    public ResponseEntity<?> cloneCase(@PathVariable Long id, @RequestBody CloneCaseRequest req) {
        log.info("POST /api/immigration/cases/{}/clone newCaseType={}", id, req.newCaseType());
        try {
            ImmigrationCaseDTO dto = caseService.cloneCase(id, req);
            return ResponseEntity.status(HttpStatus.CREATED).body(dto);
        } catch (RuntimeException e) {
            String msg = ApiErrors.safeMessage(e);
            if (msg != null && msg.startsWith("Access denied")) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", msg));
            }
            log.error("!!! cloneCase {} failed: {}", id, msg);
            return ResponseEntity.badRequest().body(Map.of("error", msg));
        }
    }

    /** Get status change history for a case. Requires READ_CASE grant. */
    @GetMapping("/{id}/status-history")
    public ResponseEntity<?> getStatusHistory(@PathVariable Long id) {
        log.info("GET /api/immigration/cases/{}/status-history", id);
        try {
            List<StatusHistoryDTO> history = caseService.getStatusHistory(id);
            return ResponseEntity.ok(history);
        } catch (RuntimeException e) {
            String msg = ApiErrors.safeMessage(e);
            if (msg != null && msg.startsWith("Access denied")) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", msg));
            }
            log.error("!!! getStatusHistory {} failed: {}", id, msg);
            return ResponseEntity.badRequest().body(Map.of("error", msg));
        }
    }
}
