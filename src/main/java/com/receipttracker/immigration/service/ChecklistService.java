package com.receipttracker.immigration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.receipttracker.immigration.dto.ChecklistItemDTO;
import com.receipttracker.immigration.dto.GenerateChecklistRequest;
import com.receipttracker.immigration.dto.UpdateChecklistItemRequest;
import com.receipttracker.immigration.model.*;
import com.receipttracker.immigration.repository.*;
import com.receipttracker.model.User;
import com.receipttracker.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class ChecklistService {

    private static final Logger log = LoggerFactory.getLogger(ChecklistService.class);

    private static final Set<String> VALID_STATUSES =
            Set.of("PENDING", "UPLOADED", "WAIVED", "VERIFIED");

    @Autowired private ChecklistItemRepository itemRepo;
    @Autowired private ChecklistTemplateRepository templateRepo;
    @Autowired private ImmigrationCaseRepository caseRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private PermissionService permissionService;
    @Autowired private ObjectMapper objectMapper;

    // ── User resolution ──────────────────────────────────────────────────────

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        OAuth2User principal = (OAuth2User) auth.getPrincipal();
        String googleId = principal.getAttribute("sub");
        return userRepo.findByGoogleId(googleId)
                .orElseThrow(() -> new RuntimeException("Authenticated user not found"));
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Generate checklist items for a case from templates.
     * Existing items with a matching itemKey are left untouched (preserves status).
     * New items are created as PENDING.
     * Requires MANAGE_CHECKLISTS grant (ATTORNEY + PARALEGAL).
     */
    @Transactional
    public List<ChecklistItemDTO> generate(Long caseId, GenerateChecklistRequest req) {
        log.info(">>> generate() caseId={} formTypes={}", caseId, req.formTypes());
        User caller = currentUser();
        permissionService.requireAccess(caller, caseId, GrantScope.MANAGE_CHECKLISTS);

        if (req.formTypes() == null || req.formTypes().isEmpty()) {
            throw new RuntimeException("At least one form type must be selected");
        }

        ImmigrationCase c = caseRepo.findById(caseId)
                .orElseThrow(() -> new RuntimeException("Case not found: " + caseId));

        List<ChecklistTemplate> templates = templateRepo.findByFormTypeInOrderBySortOrderAsc(req.formTypes());

        // Build a map of existing itemKeys so we don't overwrite them
        Map<String, ChecklistItem> existing = new HashMap<>();
        itemRepo.findByCaseIdOrderByCategoryAscSortOrderAsc(caseId)
                .forEach(it -> existing.put(it.getItemKey(), it));

        int created = 0;
        for (ChecklistTemplate t : templates) {
            if (!evaluateCondition(t.getConditionRule(), c)) continue;
            if (existing.containsKey(t.getItemKey())) continue; // already present

            ChecklistItem item = new ChecklistItem();
            item.setCaseId(caseId);
            item.setTemplateId(t.getId());
            item.setItemKey(t.getItemKey());
            item.setLabel(t.getLabel());
            item.setCategory(t.getCategory());
            item.setRequired(t.isRequired());
            item.setStatus("PENDING");
            item.setSortOrder(t.getSortOrder());
            itemRepo.save(item);
            created++;
        }
        log.info("<<< generate() caseId={} created={}", caseId, created);

        return itemRepo.findByCaseIdOrderByCategoryAscSortOrderAsc(caseId)
                .stream().map(this::toDTO).toList();
    }

    /**
     * List all checklist items for a case.
     * Requires READ_CASE grant.
     */
    @Transactional(readOnly = true)
    public List<ChecklistItemDTO> list(Long caseId) {
        log.info(">>> list() caseId={}", caseId);
        User caller = currentUser();
        permissionService.requireAccess(caller, caseId, GrantScope.READ_CASE);
        return itemRepo.findByCaseIdOrderByCategoryAscSortOrderAsc(caseId)
                .stream().map(this::toDTO).toList();
    }

    /**
     * Update a checklist item's status, linked document, or waiver reason.
     * Requires WRITE_CASE. VERIFIED status additionally requires MANAGE_CHECKLISTS.
     */
    @Transactional
    public ChecklistItemDTO update(Long caseId, Long itemId, UpdateChecklistItemRequest req) {
        log.info(">>> update() caseId={} itemId={} status={}", caseId, itemId, req.status());
        User caller = currentUser();
        permissionService.requireAccess(caller, caseId, GrantScope.WRITE_CASE);

        ChecklistItem item = itemRepo.findById(itemId)
                .orElseThrow(() -> new RuntimeException("Checklist item not found: " + itemId));
        if (!item.getCaseId().equals(caseId)) {
            throw new RuntimeException("Access denied: item does not belong to this case");
        }

        if (req.status() != null) {
            if (!VALID_STATUSES.contains(req.status())) {
                throw new RuntimeException("Invalid status: " + req.status());
            }
            // VERIFIED and WAIVED require MANAGE_CHECKLISTS (attorney/paralegal)
            if ("VERIFIED".equals(req.status()) || "WAIVED".equals(req.status())) {
                permissionService.requireAccess(caller, caseId, GrantScope.MANAGE_CHECKLISTS);
            }
            if ("WAIVED".equals(req.status()) && (req.waiverReason() == null || req.waiverReason().isBlank())) {
                throw new RuntimeException("A waiver reason is required when setting status to WAIVED");
            }
            item.setStatus(req.status());
            if ("VERIFIED".equals(req.status())) {
                item.setVerifiedByUserId(caller.getId());
                item.setVerifiedAt(LocalDateTime.now());
            } else {
                item.setVerifiedByUserId(null);
                item.setVerifiedAt(null);
            }
        }
        if (req.documentId() != null) {
            item.setDocumentId(req.documentId());
        } else if (req.documentId() == null && "PENDING".equals(req.status())) {
            item.setDocumentId(null); // clear on revert to PENDING
        }
        if (req.waiverReason() != null) {
            item.setWaiverReason(req.waiverReason().isBlank() ? null : req.waiverReason());
        }

        item = itemRepo.save(item);
        log.info("<<< update() itemId={} status={}", item.getId(), item.getStatus());
        return toDTO(item);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Evaluate a JSON condition rule against a case.
     * Returns true (include item) when rule is null or cannot be parsed.
     * Supported keys: "caseTypeIn" (List<String>), "i140Approved" (Boolean).
     */
    @SuppressWarnings("unchecked")
    private boolean evaluateCondition(String conditionRuleJson, ImmigrationCase c) {
        if (conditionRuleJson == null || conditionRuleJson.isBlank()) return true;
        try {
            Map<String, Object> rule = objectMapper.readValue(conditionRuleJson, Map.class);
            if (rule.containsKey("caseTypeIn")) {
                List<String> types = (List<String>) rule.get("caseTypeIn");
                return types.contains(c.getCaseType().name());
            }
            if (rule.containsKey("i140Approved")) {
                boolean expected = Boolean.TRUE.equals(rule.get("i140Approved"));
                return expected == c.isI140Approved();
            }
            return true;
        } catch (Exception e) {
            log.warn("Could not evaluate condition rule '{}': {}", conditionRuleJson, e.getMessage());
            return true;
        }
    }

    private ChecklistItemDTO toDTO(ChecklistItem i) {
        return new ChecklistItemDTO(
                i.getId(), i.getCaseId(), i.getTemplateId(),
                i.getItemKey(), i.getLabel(), i.getCategory(),
                i.isRequired(), i.getStatus(),
                i.getDocumentId(), i.getWaiverReason(),
                i.getVerifiedByUserId(), i.getVerifiedAt(),
                i.getSortOrder(), i.getCreatedAt(), i.getUpdatedAt()
        );
    }
}
