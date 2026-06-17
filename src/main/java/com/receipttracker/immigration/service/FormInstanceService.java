package com.receipttracker.immigration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.receipttracker.immigration.dto.FormInstanceDTO;
import com.receipttracker.immigration.model.*;
import com.receipttracker.immigration.repository.CanonicalProfileRepository;
import com.receipttracker.immigration.repository.FormInstanceRepository;
import com.receipttracker.immigration.repository.ImmigrationCaseRepository;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class FormInstanceService {

    private static final Logger log = LoggerFactory.getLogger(FormInstanceService.class);

    @Autowired private FormInstanceRepository formRepo;
    @Autowired private ImmigrationCaseRepository caseRepo;
    @Autowired private CanonicalProfileRepository profileRepo;
    @Autowired private FormMappingService mappingService;
    @Autowired private PermissionService permissionService;
    @Autowired private UserRepository userRepo;
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

    @Transactional(readOnly = true)
    public List<FormInstanceDTO> listForCase(Long caseId) {
        log.info(">>> listForCase() caseId={}", caseId);
        User user = currentUser();
        permissionService.requireAccess(user, caseId, GrantScope.READ_FORMS);

        ImmigrationCase c = caseRepo.findById(caseId)
                .orElseThrow(() -> new RuntimeException("Case not found: " + caseId));

        return formRepo.findByImmigrationCaseOrderByFormType(c)
                .stream().map(this::toDTO).toList();
    }

    @Transactional(readOnly = true)
    public FormInstanceDTO getById(Long caseId, Long formId) {
        log.info(">>> getById() caseId={} formId={}", caseId, formId);
        User user = currentUser();
        permissionService.requireAccess(user, caseId, GrantScope.READ_FORMS);

        FormInstance f = formRepo.findById(formId)
                .orElseThrow(() -> new RuntimeException("Form not found: " + formId));
        if (!f.getImmigrationCase().getId().equals(caseId)) {
            throw new RuntimeException("Form does not belong to case " + caseId);
        }
        return toDTO(f);
    }

    /**
     * Generates (or refreshes) a FormInstance for each FormType using the case's beneficiary profile.
     * Existing instances are overwritten with fresh field data; status is preserved.
     */
    @Transactional
    public List<FormInstanceDTO> generateFromProfile(Long caseId) {
        log.info(">>> generateFromProfile() caseId={}", caseId);
        User user = currentUser();
        permissionService.requireAccess(user, caseId, GrantScope.WRITE_FORMS);

        ImmigrationCase c = caseRepo.findById(caseId)
                .orElseThrow(() -> new RuntimeException("Case not found: " + caseId));

        CanonicalProfile profile = profileRepo.findByBeneficiary(c.getBeneficiary())
                .orElse(null); // null profile → all fields will be empty

        List<FormInstance> results = new ArrayList<>();
        for (FormType formType : FormType.values()) {
            Map<String, Object> fields = profile != null
                    ? mappingService.mapFields(formType, profile)
                    : Map.of();
            int completeness = profile != null
                    ? mappingService.computeCompleteness(formType, fields)
                    : 0;

            FormInstance instance = formRepo.findByImmigrationCaseAndFormType(c, formType)
                    .orElseGet(() -> {
                        FormInstance fi = new FormInstance();
                        fi.setImmigrationCase(c);
                        fi.setFormType(formType);
                        return fi;
                    });

            instance.setFieldDataJson(toJson(fields));
            instance.setCompleteness(completeness);
            // Preserve status if it's beyond DRAFT
            if (instance.getStatus() == null) {
                instance.setStatus(FormStatus.DRAFT);
            }

            results.add(formRepo.save(instance));
        }

        log.info("<<< generateFromProfile() generated {} forms for case {}", results.size(), caseId);
        return results.stream().map(this::toDTO).toList();
    }

    @Transactional
    public FormInstanceDTO updateStatus(Long caseId, Long formId, String newStatusStr) {
        log.info(">>> updateStatus() caseId={} formId={} status={}", caseId, formId, newStatusStr);
        User user = currentUser();
        permissionService.requireAccess(user, caseId, GrantScope.WRITE_FORMS);

        FormInstance f = formRepo.findById(formId)
                .orElseThrow(() -> new RuntimeException("Form not found: " + formId));
        if (!f.getImmigrationCase().getId().equals(caseId)) {
            throw new RuntimeException("Form does not belong to case " + caseId);
        }

        FormStatus newStatus;
        try {
            newStatus = FormStatus.valueOf(newStatusStr);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Unknown form status: " + newStatusStr);
        }

        f.setStatus(newStatus);
        return toDTO(formRepo.save(f));
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    FormInstanceDTO toDTO(FormInstance f) {
        return new FormInstanceDTO(
                f.getId(),
                f.getImmigrationCase().getId(),
                f.getFormType().name(),
                f.getFormType().displayName,
                f.getStatus().name(),
                parseJson(f.getFieldDataJson()),
                f.getCompleteness(),
                f.getSubmittedAt(),
                f.getNotes(),
                f.getCreatedAt(),
                f.getUpdatedAt()
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String toJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { throw new RuntimeException("JSON serialization failed", e); }
    }

    private Object parseJson(String json) {
        if (json == null || json.isBlank()) return null;
        try { return objectMapper.readValue(json, Object.class); }
        catch (Exception e) {
            log.warn("!!! Failed to parse fieldDataJson: {}", e.getMessage());
            return null;
        }
    }
}
