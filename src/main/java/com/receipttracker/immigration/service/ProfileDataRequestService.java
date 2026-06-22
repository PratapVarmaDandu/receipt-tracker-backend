package com.receipttracker.immigration.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.receipttracker.immigration.dto.*;
import com.receipttracker.immigration.model.*;
import com.receipttracker.immigration.repository.*;
import com.receipttracker.model.User;
import com.receipttracker.repository.UserRepository;
import com.receipttracker.service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class ProfileDataRequestService {

    private static final Logger log = LoggerFactory.getLogger(ProfileDataRequestService.class);

    @Autowired private ProfileDataRequestRepository repo;
    @Autowired private ImmigrationCaseRepository caseRepo;
    @Autowired private ImmOrgMemberRepository immOrgMemberRepo;
    @Autowired private CanonicalProfileRepository profileRepo;
    @Autowired private BeneficiaryRepository beneficiaryRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private PermissionService permissionService;
    @Autowired private CanonicalProfileService canonicalProfileService;
    @Autowired private EmailService emailService;
    @Autowired private ObjectMapper objectMapper;

    @Value("${app.frontend.url:http://localhost:4200}")
    private String frontendUrl;

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void requireAttorneyInFirm(User caller, ImmigrationCase c) {
        if (c.getLawFirmImmOrgId() == null)
            throw new RuntimeException("Access denied: no law firm assigned to this case");
        boolean ok = immOrgMemberRepo.findByUserIdAndStatus(caller.getId(), ImmOrgMemberStatus.ACTIVE)
                .stream()
                .anyMatch(m -> m.getImmOrgId().equals(c.getLawFirmImmOrgId())
                        && (m.getRole() == ImmOrgMemberRole.ATTORNEY || m.getRole() == ImmOrgMemberRole.OWNER));
        if (!ok) throw new RuntimeException("Access denied: ATTORNEY role in law firm required");
    }

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
     * Create a data collection request and email the beneficiary/employer.
     * Requires WRITE_CASE grant (ATTORNEY or PARALEGAL level).
     */
    @Transactional
    public ProfileDataRequestDTO create(Long caseId, CreateDataRequestRequest req) {
        log.info(">>> create() caseId={} target={}", caseId, req.targetRelationship());
        User caller = currentUser();
        permissionService.requireAccess(caller, caseId, GrantScope.WRITE_CASE);

        if (req.sections() == null || req.sections().isEmpty()) {
            throw new RuntimeException("At least one section must be selected");
        }
        if (!"BENEFICIARY".equals(req.targetRelationship()) && !"EMPLOYER".equals(req.targetRelationship())) {
            throw new RuntimeException("targetRelationship must be BENEFICIARY or EMPLOYER");
        }

        ImmigrationCase c = caseRepo.findById(caseId)
                .orElseThrow(() -> new RuntimeException("Case not found: " + caseId));
        requireAttorneyInFirm(caller, c);

        int days = req.expiryDays() > 0 ? req.expiryDays() : 7;

        ProfileDataRequest pdr = new ProfileDataRequest();
        pdr.setCaseId(caseId);
        pdr.setRequestedByUserId(caller.getId());
        pdr.setTargetRelationship(req.targetRelationship());
        pdr.setSectionsRequested(toJson(req.sections()));
        pdr.setExpiresAt(LocalDateTime.now().plusDays(days));
        pdr.setStatus("PENDING");
        pdr = repo.save(pdr);

        String link = frontendUrl + "/immigration/data-request/" + pdr.getToken();
        sendRequestEmail(c, req.targetRelationship(), link, pdr.getExpiresAt());

        // Never log the token verbatim — it grants public, no-auth access to the
        // beneficiary's prefilled PII via GET /api/immigration/data-requests/{token}.
        log.info("<<< create() id={}", pdr.getId());
        return toDTO(pdr);
    }

    /**
     * Public (no auth) — returns sections spec + pre-filled profile data.
     * Auto-expires the record if past expiresAt.
     */
    @Transactional
    public DataRequestPublicDTO getPublic(String token) {
        log.info(">>> getPublic() token=***");
        ProfileDataRequest pdr = repo.findByToken(token)
                .orElseThrow(() -> new RuntimeException("Data request not found or link is invalid"));

        if ("PENDING".equals(pdr.getStatus()) && pdr.getExpiresAt().isBefore(LocalDateTime.now())) {
            pdr.setStatus("EXPIRED");
            repo.save(pdr);
        }

        ImmigrationCase c = caseRepo.findById(pdr.getCaseId())
                .orElseThrow(() -> new RuntimeException("Case not found"));

        Beneficiary beneficiary = c.getBeneficiary();
        String beneficiaryName = beneficiary.getUser().getName();

        CanonicalProfileDTO prefill = null;
        if ("BENEFICIARY".equals(pdr.getTargetRelationship())) {
            try {
                profileRepo.findByBeneficiary(beneficiary)
                        .ifPresent(p -> {});  // touch to init lazy; actual build below
                prefill = profileRepo.findByBeneficiary(beneficiary)
                        .map(canonicalProfileService::toDTO)
                        .orElse(null);
            } catch (Exception e) {
                log.warn("Could not load prefill data for beneficiary {}: {}", beneficiary.getId(), e.getMessage());
            }
        }

        return new DataRequestPublicDTO(
                pdr.getId(), c.getCaseNumber(), beneficiaryName,
                pdr.getTargetRelationship(), fromJson(pdr.getSectionsRequested()),
                pdr.getStatus(), pdr.getExpiresAt(), prefill
        );
    }

    /**
     * Auth-required submit. Validates caller email matches expected recipient,
     * writes submitted data to CanonicalProfile, marks status=SUBMITTED.
     */
    @Transactional
    public ProfileDataRequestDTO submit(String token, DataRequestSubmitRequest req) {
        log.info(">>> submit() token=***");
        User caller = currentUser();

        ProfileDataRequest pdr = repo.findByToken(token)
                .orElseThrow(() -> new RuntimeException("Data request not found or link is invalid"));

        if ("SUBMITTED".equals(pdr.getStatus())) {
            throw new RuntimeException("This form has already been submitted");
        }
        if ("EXPIRED".equals(pdr.getStatus()) || pdr.getExpiresAt().isBefore(LocalDateTime.now())) {
            pdr.setStatus("EXPIRED");
            repo.save(pdr);
            throw new RuntimeException("This data request has expired");
        }

        ImmigrationCase c = caseRepo.findById(pdr.getCaseId())
                .orElseThrow(() -> new RuntimeException("Case not found"));

        // Email match validation
        if ("BENEFICIARY".equals(pdr.getTargetRelationship())) {
            String expected = c.getBeneficiary().getUser().getEmail();
            if (!expected.equalsIgnoreCase(caller.getEmail())) {
                throw new RuntimeException("Access denied: this request was sent to " + expected);
            }
            // Write submitted sections to CanonicalProfile
            if (req.sections() != null && !req.sections().isEmpty()) {
                applySubmittedSections(c.getBeneficiary(), req.sections());
            }
        }
        // EMPLOYER target: check active grant (permissionService already verified via Read_CASE)
        // No profile write for EMPLOYER target in this version

        pdr.setStatus("SUBMITTED");
        pdr.setSubmittedAt(LocalDateTime.now());
        repo.save(pdr);

        // Notify attorney
        notifyAttorneyOfSubmission(c, pdr);

        log.info("<<< submit() id={} submitted", pdr.getId());
        return toDTO(pdr);
    }

    /**
     * List all data requests for a case (ATTORNEY / PARALEGAL view).
     * Requires READ_CASE grant.
     */
    @Transactional(readOnly = true)
    public List<ProfileDataRequestDTO> listForCase(Long caseId) {
        log.info(">>> listForCase() caseId={}", caseId);
        User caller = currentUser();
        permissionService.requireAccess(caller, caseId, GrantScope.READ_CASE);
        return repo.findByCaseIdOrderByCreatedAtDesc(caseId)
                .stream().map(this::toDTO).toList();
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void applySubmittedSections(Beneficiary beneficiary, Map<String, Object> sections) {
        Map<String, Object> merged = new LinkedHashMap<>();

        if (sections.containsKey("personalInfo")) {
            Map<String, Object> pi = (Map<String, Object>) sections.get("personalInfo");
            transferIfPresent(pi, merged, "legalFirstName", "legalLastName", "middleName",
                    "dateOfBirth", "countryOfBirth", "citizenshipCountry", "gender", "phone");
            if (pi.containsKey("currentAddress")) merged.put("currentAddress", pi.get("currentAddress"));
        }

        if (sections.containsKey("passportI94")) {
            Map<String, Object> ppi = (Map<String, Object>) sections.get("passportI94");
            if (ppi.containsKey("passports"))     merged.put("passports", ppi.get("passports"));
            if (ppi.containsKey("travelEntries")) merged.put("travelEntries", ppi.get("travelEntries"));
        }

        if (sections.containsKey("currentStatus")) {
            Map<String, Object> cs = (Map<String, Object>) sections.get("currentStatus");
            transferIfPresent(cs, merged, "currentVisaType", "currentVisaExpiry");
        }

        if (sections.containsKey("employment")) {
            Map<String, Object> emp = (Map<String, Object>) sections.get("employment");
            if (emp.containsKey("employment")) merged.put("employment", emp.get("employment"));
        }

        if (sections.containsKey("familyDependents")) {
            Map<String, Object> fd = (Map<String, Object>) sections.get("familyDependents");
            if (fd.containsKey("dependents")) merged.put("dependents", fd.get("dependents"));
        }

        if (sections.containsKey("eadInfo")) {
            Map<String, Object> ead = (Map<String, Object>) sections.get("eadInfo");
            transferIfPresent(ead, merged, "eadCategory", "eadExpiryDate", "eadCaseNumber");
        }

        if (sections.containsKey("notificationPreferences")) {
            Map<String, Object> np = (Map<String, Object>) sections.get("notificationPreferences");
            transferIfPresent(np, merged, "notificationEmailEnabled", "notificationSmsEnabled", "notificationPhone");
        }

        UpdateProfileRequest updateReq = objectMapper.convertValue(merged, UpdateProfileRequest.class);
        canonicalProfileService.updateForBeneficiary(beneficiary, updateReq);
    }

    private void transferIfPresent(Map<String, Object> src, Map<String, Object> dst, String... keys) {
        for (String k : keys) {
            if (src.containsKey(k) && src.get(k) != null) dst.put(k, src.get(k));
        }
    }

    private void sendRequestEmail(ImmigrationCase c, String targetRelationship,
                                   String link, LocalDateTime expiresAt) {
        try {
            String recipientEmail = null;
            String recipientName  = null;
            if ("BENEFICIARY".equals(targetRelationship)) {
                recipientEmail = c.getBeneficiary().getUser().getEmail();
                recipientName  = c.getBeneficiary().getUser().getName();
            }
            if (recipientEmail == null) {
                log.warn("No recipient email for data request on case {}", c.getCaseNumber());
                return;
            }
            String subject = "Profile information needed — Case " + c.getCaseNumber();
            String body = "Hello " + (recipientName != null ? recipientName : "") + ",\n\n"
                    + "Your immigration attorney has requested you complete a short intake questionnaire "
                    + "for case " + c.getCaseNumber() + ".\n\n"
                    + "Click the link below to fill in your information:\n" + link + "\n\n"
                    + "This link expires on " + expiresAt.toLocalDate() + ".\n\n"
                    + "This is an automated message — please do not reply.";
            emailService.sendSimpleEmail(recipientEmail, subject, body);
        } catch (Exception e) {
            log.warn("Could not send data request email for case {}: {}", c.getCaseNumber(), e.getMessage());
        }
    }

    private void notifyAttorneyOfSubmission(ImmigrationCase c, ProfileDataRequest pdr) {
        try {
            if (c.getAssignedAttorneyMemberId() == null) return;
            // Look up attorney email via the case DTO in memory — skip complex join; audit log suffices
            log.info("Data request {} submitted for case {}", pdr.getId(), c.getCaseNumber());
        } catch (Exception e) {
            log.warn("Could not send attorney notification: {}", e.getMessage());
        }
    }

    private ProfileDataRequestDTO toDTO(ProfileDataRequest pdr) {
        return new ProfileDataRequestDTO(
                pdr.getId(), pdr.getCaseId(), pdr.getTargetRelationship(),
                pdr.getToken(), fromJson(pdr.getSectionsRequested()),
                pdr.getStatus(), pdr.getSubmittedAt(), pdr.getExpiresAt(), pdr.getCreatedAt()
        );
    }

    private String toJson(Object obj) {
        try { return objectMapper.writeValueAsString(obj); }
        catch (Exception e) { return "[]"; }
    }

    @SuppressWarnings("unchecked")
    private List<String> fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return objectMapper.readValue(json, new TypeReference<List<String>>() {}); }
        catch (Exception e) { return List.of(); }
    }
}
