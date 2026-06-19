package com.receipttracker.immigration.service;

import com.receipttracker.immigration.dto.CloneCaseRequest;
import com.receipttracker.immigration.dto.ConflictCheckRequest;
import com.receipttracker.immigration.dto.CreateCaseRequest;
import com.receipttracker.immigration.dto.FamilyBundleDTO;
import com.receipttracker.immigration.dto.ImmigrationCaseDTO;
import com.receipttracker.immigration.dto.StatusHistoryDTO;
import com.receipttracker.immigration.model.*;
import com.receipttracker.immigration.repository.*;
import com.receipttracker.model.User;
import com.receipttracker.repository.UserRepository;
import com.receipttracker.service.EmailService;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class CaseService {

    private static final Logger log = LoggerFactory.getLogger(CaseService.class);

    @Autowired private ImmigrationCaseRepository caseRepo;
    @Autowired private BeneficiaryRepository beneficiaryRepo;
    @Autowired private GrantRepository grantRepo;
    @Autowired private ImmOrgRepository immOrgRepo;
    @Autowired private ImmOrgMemberRepository immOrgMemberRepo;
    @Autowired private StatusHistoryRepository statusHistoryRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private PermissionService permissionService;
    @Autowired private AuditService auditService;
    @Autowired private EmailService emailService;

    private final AtomicInteger caseCounter = new AtomicInteger(1);

    @PostConstruct
    void initCaseCounter() {
        String prefix = "IMM-" + Year.now().getValue() + "-";
        caseRepo.findMaxCaseNumberWithPrefix(prefix).ifPresent(max -> {
            try {
                int seq = Integer.parseInt(max.substring(prefix.length()));
                caseCounter.set(seq + 1);
                log.info("Case counter initialized to {} from DB", seq + 1);
            } catch (NumberFormatException ignored) {}
        });
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

    @Transactional
    public ImmigrationCaseDTO create(CreateCaseRequest req) {
        log.info(">>> create() caseType={}", req.caseType());
        User caller = currentUser();

        CaseType caseType;
        try {
            caseType = CaseType.valueOf(req.caseType());
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Unknown caseType: " + req.caseType());
        }

        // Determine caller's org memberships
        List<ImmOrgMember> callerMemberships = immOrgMemberRepo
                .findByUserIdAndStatus(caller.getId(), ImmOrgMemberStatus.ACTIVE);
        boolean isOrgMember = !callerMemberships.isEmpty();

        if (!isOrgMember) {
            throw new RuntimeException(
                "Only employer or law firm members can create cases. Beneficiaries are invited by their employer.");
        }

        // beneficiaryEmail is required for all org-member-created cases
        if (req.beneficiaryEmail() == null || req.beneficiaryEmail().isBlank()) {
            throw new RuntimeException("beneficiaryEmail is required");
        }

        // Validate employer org membership if provided
        ImmOrg employerOrg = null;
        if (req.employerImmOrgId() != null) {
            employerOrg = immOrgRepo.findById(req.employerImmOrgId())
                    .orElseThrow(() -> new RuntimeException("Employer org not found: " + req.employerImmOrgId()));
            Long employerOrgId = employerOrg.getId();
            boolean callerInEmployerOrg = callerMemberships.stream()
                    .anyMatch(m -> m.getImmOrgId().equals(employerOrgId));
            if (!callerInEmployerOrg) {
                log.info("Caller is not a member of employer org {} — allowing through", employerOrgId);
            }
        }

        // Validate law firm org if provided
        ImmOrg lawFirmOrg = null;
        if (req.lawFirmImmOrgId() != null) {
            lawFirmOrg = immOrgRepo.findById(req.lawFirmImmOrgId())
                    .orElseThrow(() -> new RuntimeException("Law firm org not found: " + req.lawFirmImmOrgId()));
        }

        // Validate H4-EAD: parent H1B case must have i140Approved = true
        if (caseType == CaseType.H4_EAD && req.parentCaseId() != null) {
            ImmigrationCase parent = caseRepo.findById(req.parentCaseId())
                    .orElseThrow(() -> new RuntimeException("Parent case not found: " + req.parentCaseId()));
            if (!parent.isI140Approved()) {
                throw new RuntimeException(
                    "H-4 EAD requires an approved I-140 on the primary H-1B case (case "
                    + parent.getCaseNumber() + " does not have an approved I-140 yet)");
            }
        }

        // Find or create beneficiary user
        String beneficiaryEmail = req.beneficiaryEmail().toLowerCase().trim();
        User beneficiaryUser = findOrCreateStubUser(beneficiaryEmail);
        Beneficiary beneficiary = beneficiaryRepo.findByUser(beneficiaryUser)
                .orElseGet(() -> {
                    Beneficiary b = new Beneficiary();
                    b.setUser(beneficiaryUser);
                    return beneficiaryRepo.save(b);
                });

        // Build the case
        ImmigrationCase c = new ImmigrationCase();
        c.setCaseNumber(generateCaseNumber());
        c.setBeneficiary(beneficiary);
        c.setEmployerImmOrgId(employerOrg != null ? employerOrg.getId() : null);
        c.setLawFirmImmOrgId(lawFirmOrg != null ? lawFirmOrg.getId() : null);
        c.setCaseType(caseType);
        c.setStatus(CaseStatus.PROSPECTIVE);
        c.setParentCaseId(req.parentCaseId());
        c.setAssignedAttorneyMemberId(req.assignedAttorneyMemberId());
        c.setCreatedBy(caller);

        // Mark invite pending if beneficiary is a stub (hasn't logged in yet)
        boolean isStub = beneficiaryUser.getGoogleId().startsWith("PENDING_");
        if (isStub) {
            c.setBeneficiaryInviteToken(UUID.randomUUID().toString());
            c.setBeneficiaryInviteEmail(beneficiaryEmail);
        }

        ImmigrationCase saved = caseRepo.save(c);

        // Grant beneficiary all scopes
        grantAllScopes(saved, beneficiaryUser, CaseRelationship.BENEFICIARY, caller);

        // Grant employer org: read case + docs + messaging
        if (employerOrg != null) {
            grantOrgScopes(saved, employerOrg.getId(), CaseRelationship.HR_ADMIN, caller,
                    GrantScope.READ_CASE, GrantScope.READ_DOCS, GrantScope.MESSAGING);
        }

        // Grant law firm all scopes
        if (lawFirmOrg != null) {
            grantOrgScopes(saved, lawFirmOrg.getId(), CaseRelationship.ATTORNEY, caller,
                    GrantScope.values());
        }

        auditService.appendSystem(saved, "CASE_CREATED", "{\"caseType\":\"" + caseType.name() + "\"}");

        // Send email invite if beneficiary is a new stub user
        if (isStub) {
            sendBeneficiaryInviteEmail(saved, beneficiaryEmail, caller);
        }

        log.info("<<< create() caseId={} caseNumber={} beneficiary={}",
                saved.getId(), saved.getCaseNumber(), beneficiaryEmail);
        return toDTO(saved, caller);
    }

    @Transactional(readOnly = true)
    public List<ImmigrationCaseDTO> listAccessible() {
        log.info(">>> listAccessible()");
        User user = currentUser();
        List<Long> orgIds = permissionService.activeImmOrgIds(user);
        return caseRepo.findAccessibleByUser(user.getId(), orgIds)
                .stream().map(c -> toDTO(c, user)).toList();
    }

    @Transactional(readOnly = true)
    public ImmigrationCaseDTO getById(Long caseId) {
        log.info(">>> getById() caseId={}", caseId);
        User user = currentUser();
        permissionService.requireAccess(user, caseId, GrantScope.READ_CASE);
        ImmigrationCase c = caseRepo.findById(caseId)
                .orElseThrow(() -> new RuntimeException("Case not found: " + caseId));
        return toDTO(c, user);
    }

    @Transactional
    public ImmigrationCaseDTO updateStatus(Long caseId, String newStatusStr) {
        log.info(">>> updateStatus() caseId={} newStatus={}", caseId, newStatusStr);
        User user = currentUser();
        permissionService.requireAccess(user, caseId, GrantScope.WRITE_CASE);

        ImmigrationCase c = caseRepo.findById(caseId)
                .orElseThrow(() -> new RuntimeException("Case not found: " + caseId));

        CaseStatus newStatus;
        try {
            newStatus = CaseStatus.valueOf(newStatusStr);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Unknown status: " + newStatusStr);
        }

        if (!c.getStatus().canTransitionTo(newStatus)) {
            throw new RuntimeException(
                "Invalid transition: " + c.getStatus() + " → " + newStatus);
        }

        CaseStatus oldStatus = c.getStatus();
        c.setStatus(newStatus);

        // Auto-flag I-140 approval on I140 cases reaching PETITION_APPROVED
        if (newStatus == CaseStatus.PETITION_APPROVED
                && (c.getCaseType() == CaseType.I140_EB2 || c.getCaseType() == CaseType.I140_EB3)) {
            c.setI140Approved(true);
            c.setI140ApprovedDate(LocalDate.now());
            log.info("I-140 approved for case {} — i140Approved flag set", caseId);
        }

        ImmigrationCase saved = caseRepo.save(c);

        // Persist immutable status history row
        StatusHistory hist = new StatusHistory();
        hist.setImmigrationCase(saved);
        hist.setFromStatus(oldStatus.name());
        hist.setToStatus(newStatus.name());
        hist.setChangedByUserId(user.getId());
        statusHistoryRepo.save(hist);

        auditService.append(saved, user, "STATUS_CHANGED",
                "{\"from\":\"" + oldStatus.name() + "\",\"to\":\"" + newStatus.name() + "\"}",
                FeedVisibility.ALL);
        return toDTO(saved, user);
    }

    @Transactional(readOnly = true)
    public List<ImmigrationCaseDTO> listByOrg(Long orgId) {
        log.info(">>> listByOrg() orgId={}", orgId);
        User user = currentUser();
        requireOrgMember(user, orgId);

        List<ImmigrationCase> cases = new ArrayList<>();
        cases.addAll(caseRepo.findByEmployerImmOrgIdOrderByCreatedAtDesc(orgId));
        cases.addAll(caseRepo.findByLawFirmImmOrgIdOrderByCreatedAtDesc(orgId));

        return cases.stream()
                .distinct()
                .map(c -> toDTO(c, user))
                .toList();
    }

    // ── Case join (beneficiary invite) ───────────────────────────────────────

    @Transactional(readOnly = true)
    public ImmigrationCaseDTO getByInviteToken(String token) {
        log.info(">>> getByInviteToken()");
        ImmigrationCase c = caseRepo.findByBeneficiaryInviteToken(token)
                .orElseThrow(() -> new RuntimeException("Invalid invite link"));
        // Return without a caller — public view, minimal info
        return toDTOPublic(c);
    }

    @Transactional
    public ImmigrationCaseDTO acceptInvite(String token) {
        log.info(">>> acceptInvite()");
        User caller = currentUser();

        ImmigrationCase c = caseRepo.findByBeneficiaryInviteToken(token)
                .orElseThrow(() -> new RuntimeException("Invalid invite link"));

        if (c.getBeneficiaryInviteEmail() == null
                || !c.getBeneficiaryInviteEmail().equalsIgnoreCase(caller.getEmail())) {
            throw new RuntimeException("This invite is not for your account (" + caller.getEmail() + ")");
        }

        // Link caller as the real beneficiary (stub → real)
        Beneficiary beneficiary = beneficiaryRepo.findByUser(caller)
                .orElseGet(() -> {
                    Beneficiary b = new Beneficiary();
                    b.setUser(caller);
                    return beneficiaryRepo.save(b);
                });

        // Update beneficiary on case if it was a stub
        User stubUser = c.getBeneficiary().getUser();
        if (!stubUser.getId().equals(caller.getId())) {
            // Re-point grants from stub to real user
            grantRepo.findByImmigrationCaseAndRevokedAtIsNull(c).stream()
                    .filter(g -> g.getSubjectUser() != null
                              && g.getSubjectUser().getId().equals(stubUser.getId()))
                    .forEach(g -> {
                        g.setSubjectUser(caller);
                        grantRepo.save(g);
                    });
            c.setBeneficiary(beneficiary);
        }

        // Clear invite fields — invite consumed
        c.setBeneficiaryInviteToken(null);
        c.setBeneficiaryInviteEmail(null);
        ImmigrationCase saved = caseRepo.save(c);

        auditService.append(saved, caller, "BENEFICIARY_JOINED",
                "{\"email\":\"" + caller.getEmail() + "\"}", FeedVisibility.ALL);

        log.info("<<< acceptInvite() caseId={} callerEmail={}", saved.getId(), caller.getEmail());
        return toDTO(saved, caller);
    }

    // ── Paralegal assignment ─────────────────────────────────────────────────

    @Transactional
    public ImmigrationCaseDTO assignParalegal(Long caseId, Long memberId) {
        log.info(">>> assignParalegal() caseId={} memberId={}", caseId, memberId);
        User caller = currentUser();
        permissionService.requireAccess(caller, caseId, GrantScope.WRITE_CASE);

        ImmigrationCase c = caseRepo.findById(caseId)
                .orElseThrow(() -> new RuntimeException("Case not found: " + caseId));

        // memberId == null means remove paralegal
        if (memberId != null) {
            ImmOrgMember member = immOrgMemberRepo.findById(memberId)
                    .orElseThrow(() -> new RuntimeException("Member not found: " + memberId));
            // Ensure the paralegal belongs to the same law firm as the case
            if (c.getLawFirmImmOrgId() != null && !member.getImmOrgId().equals(c.getLawFirmImmOrgId())) {
                throw new RuntimeException("Member does not belong to the law firm for this case");
            }
            // Grant PARALEGAL scope if not already granted
            if (member.getUserId() != null) {
                User paralegalUser = userRepo.findById(member.getUserId()).orElse(null);
                if (paralegalUser != null) {
                    boolean alreadyGranted = grantRepo.findByImmigrationCaseAndRevokedAtIsNull(c).stream()
                            .anyMatch(g -> g.getSubjectUser() != null
                                    && g.getSubjectUser().getId().equals(paralegalUser.getId())
                                    && g.getRelationship() == CaseRelationship.PARALEGAL);
                    if (!alreadyGranted) {
                        grantAllScopes(c, paralegalUser, CaseRelationship.PARALEGAL, caller);
                    }
                }
            }
        }

        c.setAssignedParalegalMemberId(memberId);
        ImmigrationCase saved = caseRepo.save(c);
        auditService.append(saved, caller, "PARALEGAL_ASSIGNED",
                "{\"memberId\":" + memberId + "}", FeedVisibility.ATTORNEY_ONLY);
        log.info("<<< assignParalegal() caseId={} memberId={}", caseId, memberId);
        return toDTO(saved, caller);
    }

    // ── Status history (FEAT-QW4) ────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<StatusHistoryDTO> getStatusHistory(Long caseId) {
        log.info(">>> getStatusHistory() caseId={}", caseId);
        User user = currentUser();
        permissionService.requireAccess(user, caseId, GrantScope.READ_CASE);
        ImmigrationCase c = caseRepo.findById(caseId)
                .orElseThrow(() -> new RuntimeException("Case not found: " + caseId));
        return statusHistoryRepo.findByImmigrationCaseOrderByChangedAtDesc(c)
                .stream().map(this::toStatusHistoryDTO).toList();
    }

    // ── Case cloning (FEAT-QW3) ──────────────────────────────────────────────

    @Transactional
    public ImmigrationCaseDTO cloneCase(Long sourceId, CloneCaseRequest req) {
        log.info(">>> cloneCase() sourceId={} newCaseType={}", sourceId, req.newCaseType());
        User caller = currentUser();
        permissionService.requireAccess(caller, sourceId, GrantScope.WRITE_CASE);

        ImmigrationCase src = caseRepo.findById(sourceId)
                .orElseThrow(() -> new RuntimeException("Case not found: " + sourceId));

        CaseType newCaseType;
        try {
            newCaseType = CaseType.valueOf(req.newCaseType());
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Unknown caseType: " + req.newCaseType());
        }

        ImmigrationCase clone = new ImmigrationCase();
        clone.setCaseNumber(generateCaseNumber());
        clone.setBeneficiary(src.getBeneficiary());
        clone.setEmployerImmOrgId(src.getEmployerImmOrgId());
        clone.setLawFirmImmOrgId(src.getLawFirmImmOrgId());
        clone.setAssignedAttorneyMemberId(src.getAssignedAttorneyMemberId());
        clone.setCaseType(newCaseType);
        clone.setStatus(CaseStatus.PROSPECTIVE);
        clone.setCreatedBy(caller);
        // parentCaseId, beneficiaryInviteToken — not cloned; beneficiary already linked

        ImmigrationCase saved = caseRepo.save(clone);

        // Copy all active grants from source case to the clone
        List<Grant> sourceGrants = grantRepo.findByImmigrationCaseAndRevokedAtIsNull(src);
        for (Grant srcGrant : sourceGrants) {
            Grant g = new Grant();
            g.setImmigrationCase(saved);
            g.setSubjectUser(srcGrant.getSubjectUser());
            g.setSubjectImmOrgId(srcGrant.getSubjectImmOrgId());
            g.setRelationship(srcGrant.getRelationship());
            g.setScope(srcGrant.getScope());
            g.setGrantedBy(caller);
            grantRepo.save(g);
        }

        auditService.append(saved, caller, "CASE_CLONED",
                "{\"clonedFromCaseId\":" + sourceId + ",\"newCaseType\":\"" + newCaseType.name() + "\"}",
                FeedVisibility.ALL);

        log.info("<<< cloneCase() newCaseId={} caseNumber={}", saved.getId(), saved.getCaseNumber());
        return toDTO(saved, caller);
    }

    // ── Conflict check (FEAT-QW5) ────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ImmigrationCaseDTO> checkConflict(ConflictCheckRequest req) {
        log.info(">>> checkConflict() email={}", req.beneficiaryEmail());
        User caller = currentUser();

        // Require ATTORNEY or OWNER role in a LAW_FIRM org
        List<Long> callerLawFirmIds = immOrgMemberRepo
                .findByUserIdAndStatus(caller.getId(), ImmOrgMemberStatus.ACTIVE)
                .stream()
                .filter(m -> m.getRole() == ImmOrgMemberRole.ATTORNEY || m.getRole() == ImmOrgMemberRole.OWNER)
                .map(ImmOrgMember::getImmOrgId)
                .filter(orgId -> immOrgRepo.findById(orgId)
                        .map(o -> o.getOrgType() == ImmOrgType.LAW_FIRM)
                        .orElse(false))
                .distinct()
                .collect(Collectors.toList());

        if (callerLawFirmIds.isEmpty()) {
            throw new RuntimeException("Access denied: ATTORNEY or OWNER role in a law firm required");
        }

        String emailLower = req.beneficiaryEmail() != null
                ? req.beneficiaryEmail().trim().toLowerCase() : "";

        Set<Long> seen = new java.util.HashSet<>();
        return callerLawFirmIds.stream()
                .flatMap(id -> caseRepo.findByLawFirmImmOrgIdOrderByCreatedAtDesc(id).stream())
                .filter(c -> {
                    boolean emailMatch = !emailLower.isEmpty()
                            && c.getBeneficiary().getUser().getEmail().toLowerCase().equals(emailLower);
                    boolean orgMatch = req.employerOrgId() != null
                            && req.employerOrgId().equals(c.getEmployerImmOrgId());
                    return emailMatch || orgMatch;
                })
                .filter(c -> seen.add(c.getId()))
                .map(c -> toDTO(c, caller))
                .collect(Collectors.toList());
    }

    // ── Family bundle (FEAT-QW6) ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    public FamilyBundleDTO getFamily(Long caseId) {
        log.info(">>> getFamily() caseId={}", caseId);
        User caller = currentUser();
        permissionService.requireAccess(caller, caseId, GrantScope.READ_CASE);

        ImmigrationCase primary = caseRepo.findById(caseId)
                .orElseThrow(() -> new RuntimeException("Case not found: " + caseId));
        ImmigrationCaseDTO primaryDTO = toDTO(primary, caller);

        List<ImmigrationCaseDTO> dependentDTOs = caseRepo
                .findByParentCaseIdOrderByCreatedAtDesc(caseId)
                .stream()
                .map(c -> toDTO(c, caller))
                .collect(Collectors.toList());

        return new FamilyBundleDTO(primaryDTO, dependentDTOs);
    }

    // ── Grant helpers ────────────────────────────────────────────────────────

    @Transactional
    public void grantUserAccess(Long caseId, Long targetUserId, String relationship, String scope) {
        User caller = currentUser();
        permissionService.requireAccess(caller, caseId, GrantScope.WRITE_CASE);

        ImmigrationCase c = caseRepo.findById(caseId)
                .orElseThrow(() -> new RuntimeException("Case not found: " + caseId));
        User target = userRepo.findById(targetUserId)
                .orElseThrow(() -> new RuntimeException("User not found: " + targetUserId));

        Grant g = new Grant();
        g.setImmigrationCase(c);
        g.setSubjectUser(target);
        g.setRelationship(CaseRelationship.valueOf(relationship));
        g.setScope(GrantScope.valueOf(scope));
        g.setGrantedBy(caller);
        grantRepo.save(g);
        log.info("<<< grantUserAccess() userId={} caseId={} scope={}", targetUserId, caseId, scope);
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    ImmigrationCaseDTO toDTO(ImmigrationCase c, User caller) {
        String employerName    = orgName(c.getEmployerImmOrgId());
        String lawFirmName     = orgName(c.getLawFirmImmOrgId());
        String attorneyName    = attorneyName(c.getAssignedAttorneyMemberId());
        String attorneyEmail   = attorneyEmail(c.getAssignedAttorneyMemberId());
        String paralegalName   = attorneyName(c.getAssignedParalegalMemberId());
        String paralegalEmail  = attorneyEmail(c.getAssignedParalegalMemberId());

        return new ImmigrationCaseDTO(
                c.getId(),
                c.getCaseNumber(),
                c.getBeneficiary().getId(),
                c.getBeneficiary().getUser().getName(),
                c.getBeneficiary().getUser().getEmail(),
                c.getEmployerImmOrgId(), employerName,
                c.getLawFirmImmOrgId(),  lawFirmName,
                c.getCaseType().name(),
                c.getStatus().name(),
                c.getPriorityDate(),
                c.getReceiptNumber(),
                c.getParentCaseId(),
                c.isI140Approved(),
                c.getI140ApprovedDate(),
                c.getAssignedAttorneyMemberId(),
                attorneyName,
                attorneyEmail,
                c.getAssignedParalegalMemberId(),
                paralegalName,
                paralegalEmail,
                c.getBeneficiaryInviteToken() != null,
                c.getCreatedBy().getId(),
                c.getCreatedAt(),
                c.getUpdatedAt(),
                computeCallerRelationship(c, caller)
        );
    }

    // Minimal DTO for public invite view (no auth context)
    private ImmigrationCaseDTO toDTOPublic(ImmigrationCase c) {
        return new ImmigrationCaseDTO(
                c.getId(),
                c.getCaseNumber(),
                null, null, null,
                c.getEmployerImmOrgId(), orgName(c.getEmployerImmOrgId()),
                c.getLawFirmImmOrgId(),  orgName(c.getLawFirmImmOrgId()),
                c.getCaseType().name(),
                c.getStatus().name(),
                null, null,
                c.getParentCaseId(),
                c.isI140Approved(), null,
                null, null, null,
                null, null, null,
                true,
                null, null, null,
                null
        );
    }

    private StatusHistoryDTO toStatusHistoryDTO(StatusHistory h) {
        String name = null;
        if (h.getChangedByUserId() != null) {
            name = userRepo.findById(h.getChangedByUserId()).map(User::getName).orElse(null);
        }
        return new StatusHistoryDTO(
                h.getId(),
                h.getImmigrationCase().getId(),
                h.getFromStatus(),
                h.getToStatus(),
                h.getChangedByUserId(),
                name,
                h.getChangedAt(),
                h.getNote()
        );
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private User findOrCreateStubUser(String email) {
        return userRepo.findByEmail(email).orElseGet(() -> {
            User stub = new User();
            stub.setEmail(email);
            // Placeholder googleId — replaced when the real user logs in via Google
            stub.setGoogleId("PENDING_" + UUID.randomUUID());
            stub.setName(email);  // name filled in when they actually log in
            User saved = userRepo.save(stub);
            log.info("Created stub user for invite email={}", email);
            return saved;
        });
    }

    private void sendBeneficiaryInviteEmail(ImmigrationCase c, String toEmail, User invitedBy) {
        try {
            String subject = "You have been invited to a visa case";
            String body = String.format(
                "Hi,\n\n%s has opened a visa sponsorship case for you.\n\n" +
                "Case: %s (%s)\n" +
                "Employer: %s\n" +
                "Law Firm: %s\n\n" +
                "To view your case, sign in at your Visa Tracker and follow the invitation link:\n" +
                "/immigration/cases/join/%s\n\n" +
                "Once you sign in, your case will be visible in your Visa Tracker dashboard.",
                invitedBy.getName() != null ? invitedBy.getName() : invitedBy.getEmail(),
                c.getCaseNumber(), c.getCaseType().name(),
                orgName(c.getEmployerImmOrgId()) != null ? orgName(c.getEmployerImmOrgId()) : "N/A",
                orgName(c.getLawFirmImmOrgId()) != null ? orgName(c.getLawFirmImmOrgId()) : "N/A",
                c.getBeneficiaryInviteToken()
            );
            emailService.sendSimpleEmail(toEmail, subject, body);
        } catch (Exception e) {
            // Non-fatal — email failure never blocks case creation
            log.warn("Beneficiary invite email failed for {}: {}", toEmail, e.getMessage());
        }
    }

    private String orgName(Long orgId) {
        if (orgId == null) return null;
        return immOrgRepo.findById(orgId).map(ImmOrg::getName).orElse(null);
    }

    private String attorneyName(Long memberId) {
        if (memberId == null) return null;
        return immOrgMemberRepo.findById(memberId)
                .flatMap(m -> m.getUserId() != null ? userRepo.findById(m.getUserId()) : java.util.Optional.empty())
                .map(User::getName)
                .orElse(null);
    }

    private String attorneyEmail(Long memberId) {
        if (memberId == null) return null;
        return immOrgMemberRepo.findById(memberId).map(ImmOrgMember::getEmail).orElse(null);
    }

    private String computeCallerRelationship(ImmigrationCase c, User caller) {
        List<Grant> grants = grantRepo.findByImmigrationCaseAndRevokedAtIsNull(c);
        List<Long> callerOrgIds = immOrgMemberRepo
                .findByUserIdAndStatus(caller.getId(), ImmOrgMemberStatus.ACTIVE)
                .stream().map(ImmOrgMember::getImmOrgId).toList();

        List<CaseRelationship> matches = grants.stream()
                .filter(g -> (g.getSubjectUser() != null && g.getSubjectUser().getId().equals(caller.getId()))
                          || (g.getSubjectImmOrgId() != null && callerOrgIds.contains(g.getSubjectImmOrgId())))
                .map(Grant::getRelationship)
                .distinct()
                .toList();

        if (matches.contains(CaseRelationship.ATTORNEY) || matches.contains(CaseRelationship.PARALEGAL)) return "ATTORNEY";
        if (matches.contains(CaseRelationship.HR_ADMIN)) return "HR_ADMIN";
        if (matches.contains(CaseRelationship.BENEFICIARY)) return "BENEFICIARY";
        if (!matches.isEmpty()) return "VIEWER";
        return null;
    }

    private void requireOrgMember(User user, Long orgId) {
        boolean isMember = immOrgMemberRepo
                .findByUserIdAndStatus(user.getId(), ImmOrgMemberStatus.ACTIVE)
                .stream().anyMatch(m -> m.getImmOrgId().equals(orgId));
        if (!isMember) {
            throw new RuntimeException("Access denied: not a member of org " + orgId);
        }
    }

    private void grantAllScopes(ImmigrationCase c, User subject, CaseRelationship relationship, User grantedBy) {
        for (GrantScope scope : GrantScope.values()) {
            Grant g = new Grant();
            g.setImmigrationCase(c);
            g.setSubjectUser(subject);
            g.setRelationship(relationship);
            g.setScope(scope);
            g.setGrantedBy(grantedBy);
            grantRepo.save(g);
        }
    }

    private void grantOrgScopes(ImmigrationCase c, Long orgId, CaseRelationship relationship,
                                 User grantedBy, GrantScope... scopes) {
        for (GrantScope scope : scopes) {
            Grant g = new Grant();
            g.setImmigrationCase(c);
            g.setSubjectImmOrgId(orgId);
            g.setRelationship(relationship);
            g.setScope(scope);
            g.setGrantedBy(grantedBy);
            grantRepo.save(g);
        }
    }

    private String generateCaseNumber() {
        return String.format("IMM-%d-%04d", Year.now().getValue(), caseCounter.getAndIncrement());
    }
}
