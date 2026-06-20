package com.receipttracker.immigration.controller;

import com.receipttracker.immigration.dto.CaseAuditDTO;
import com.receipttracker.immigration.model.GrantScope;
import com.receipttracker.immigration.service.AuditService;
import com.receipttracker.immigration.service.PermissionService;
import com.receipttracker.model.User;
import com.receipttracker.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/immigration/cases")
public class ImmAuditController {

    private static final Logger log = LoggerFactory.getLogger(ImmAuditController.class);

    @Autowired private AuditService auditService;
    @Autowired private PermissionService permissionService;
    @Autowired private UserRepository userRepo;

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        OAuth2User principal = (OAuth2User) auth.getPrincipal();
        String googleId = principal.getAttribute("sub");
        return userRepo.findByGoogleId(googleId)
                .orElseThrow(() -> new RuntimeException("Authenticated user not found"));
    }

    /**
     * GET /api/immigration/cases/{id}/audit
     * Returns merged audit view grouped by type.
     * Restricted to ATTORNEY + FIRM_ADMIN (APPROVE_FORMS scope).
     */
    @GetMapping("/{id}/audit")
    public ResponseEntity<?> getCaseAudit(@PathVariable Long id) {
        log.info(">>> getCaseAudit() caseId={}", id);
        try {
            User caller = currentUser();
            permissionService.requireAccess(caller, id, GrantScope.APPROVE_FORMS);
            CaseAuditDTO audit = auditService.getCaseAudit(id);
            log.info("<<< getCaseAudit() caseId={}", id);
            return ResponseEntity.ok(audit);
        } catch (Exception e) {
            log.error("!!! getCaseAudit() caseId={}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
