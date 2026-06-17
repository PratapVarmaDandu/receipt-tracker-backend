package com.receipttracker.immigration.controller;

import com.receipttracker.immigration.model.*;
import com.receipttracker.immigration.repository.*;
import com.receipttracker.model.User;
import com.receipttracker.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Local-only dev helpers. Never active in prod or test profiles.
 */
@Profile("local")
@RestController
@RequestMapping("/api/dev")
public class DevSeedController {

    private static final Logger log = LoggerFactory.getLogger(DevSeedController.class);

    @Autowired private UserRepository userRepo;
    @Autowired private BeneficiaryRepository beneficiaryRepo;
    @Autowired private ImmigrationCaseRepository caseRepo;
    @Autowired private ImmOrgRepository immOrgRepo;
    @Autowired private ImmOrgMemberRepository immOrgMemberRepo;
    @Autowired private OrgPartnershipRepository partnershipRepo;
    @Autowired private GrantRepository grantRepo;

    @PostMapping("/imm/seed")
    @Transactional
    public ResponseEntity<?> seedImmigrationScenario() {
        // Idempotent — skip if already seeded
        if (userRepo.findByEmail("beneficiary@test.local").isPresent()) {
            long caseId = caseRepo.findAll().stream()
                    .filter(c -> c.getBeneficiary() != null
                            && "beneficiary@test.local".equals(c.getBeneficiary().getUser().getEmail()))
                    .mapToLong(ImmigrationCase::getId).findFirst().orElse(0L);
            return ResponseEntity.ok(Map.of(
                    "message", "Already seeded",
                    "beneficiaryEmail", "beneficiary@test.local",
                    "hrEmail", "hr@test.local",
                    "attorneyEmail", "attorney@test.local",
                    "caseId", caseId));
        }

        User ben = createUser("dev-ben", "beneficiary@test.local", "John Doe");
        User hr  = createUser("dev-hr",  "hr@test.local",          "HR Manager");
        User atty = createUser("dev-atty","attorney@test.local",   "Jane Attorney");

        ImmOrg employer = new ImmOrg();
        employer.setName("Acme Corp");
        employer.setOrgType(ImmOrgType.EMPLOYER);
        employer.setOwnerUserId(hr.getId());
        employer = immOrgRepo.save(employer);

        ImmOrg lawFirm = new ImmOrg();
        lawFirm.setName("Dewey Cheatham & Howe LLP");
        lawFirm.setOrgType(ImmOrgType.LAW_FIRM);
        lawFirm.setOwnerUserId(atty.getId());
        lawFirm = immOrgRepo.save(lawFirm);

        addMember(employer.getId(), hr.getId(),   hr.getEmail(),   ImmOrgMemberRole.OWNER);
        addMember(lawFirm.getId(),  atty.getId(), atty.getEmail(), ImmOrgMemberRole.OWNER);

        OrgPartnership p = new OrgPartnership();
        p.setEmployerOrgId(employer.getId());
        p.setLawFirmOrgId(lawFirm.getId());
        p.setStatus(OrgPartnershipStatus.ACTIVE);
        p.setInitiatedByUserId(hr.getId());
        partnershipRepo.save(p);

        Beneficiary beneficiary = beneficiaryRepo.findByUser(ben).orElseGet(() -> {
            Beneficiary b = new Beneficiary();
            b.setUser(ben);
            return beneficiaryRepo.save(b);
        });

        ImmigrationCase c = new ImmigrationCase();
        c.setCaseNumber("IMM-SEED-0001");
        c.setBeneficiary(beneficiary);
        c.setEmployerImmOrgId(employer.getId());
        c.setLawFirmImmOrgId(lawFirm.getId());
        c.setCaseType(CaseType.H1B_INITIAL);
        c.setStatus(CaseStatus.DATA_COLLECTION);
        c.setCreatedBy(ben);
        ImmigrationCase saved = caseRepo.save(c);

        grantAllScopes(saved, ben, CaseRelationship.BENEFICIARY, ben);
        grantOrgScopes(saved, employer.getId(), CaseRelationship.HR_ADMIN, ben,
                GrantScope.READ_CASE, GrantScope.READ_DOCS, GrantScope.MESSAGING);
        grantOrgScopes(saved, lawFirm.getId(), CaseRelationship.ATTORNEY, ben,
                GrantScope.values());

        log.info("IMM seed complete caseId={}", saved.getId());
        return ResponseEntity.ok(Map.of(
                "message", "Seed complete",
                "beneficiaryEmail", ben.getEmail(),
                "hrEmail", hr.getEmail(),
                "attorneyEmail", atty.getEmail(),
                "caseId", saved.getId()));
    }

    @PostMapping("/switch-user")
    public ResponseEntity<?> switchUser(@RequestParam String email,
                                        jakarta.servlet.http.HttpServletRequest request) {
        User user = userRepo.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found: " + email));

        Map<String, Object> attrs = new java.util.HashMap<>();
        attrs.put("sub",   user.getGoogleId());
        attrs.put("email", user.getEmail());
        attrs.put("name",  user.getName() != null ? user.getName() : email);

        var oauth2User = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                attrs, "sub");

        var auth = new UsernamePasswordAuthenticationToken(
                oauth2User, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));

        // Build a fresh SecurityContext and set it both in the holder and in the
        // HTTP session. Spring Security 6 no longer auto-saves context changes to
        // the session, so we write the session attribute directly so the next
        // request loads the switched user instead of dev@localhost.local.
        org.springframework.security.core.context.SecurityContext ctx =
                org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);

        request.getSession(true).setAttribute(
                org.springframework.security.web.context.HttpSessionSecurityContextRepository
                        .SPRING_SECURITY_CONTEXT_KEY,
                ctx);

        log.info("DEV switch-user → {}", email);
        return ResponseEntity.ok(Map.of("switched", true, "email", user.getEmail(),
                "name", user.getName() != null ? user.getName() : ""));
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private User createUser(String googleId, String email, String name) {
        return userRepo.findByEmail(email).orElseGet(() -> {
            User u = new User();
            u.setGoogleId(googleId);
            u.setEmail(email);
            u.setName(name);
            return userRepo.save(u);
        });
    }

    private void addMember(Long orgId, Long userId, String email, ImmOrgMemberRole role) {
        ImmOrgMember m = new ImmOrgMember();
        m.setImmOrgId(orgId);
        m.setUserId(userId);
        m.setEmail(email);
        m.setRole(role);
        m.setStatus(ImmOrgMemberStatus.ACTIVE);
        immOrgMemberRepo.save(m);
    }

    private void grantAllScopes(ImmigrationCase c, User subject, CaseRelationship rel, User grantedBy) {
        for (GrantScope scope : GrantScope.values()) {
            Grant g = new Grant();
            g.setImmigrationCase(c);
            g.setSubjectUser(subject);
            g.setRelationship(rel);
            g.setScope(scope);
            g.setGrantedBy(grantedBy);
            grantRepo.save(g);
        }
    }

    private void grantOrgScopes(ImmigrationCase c, Long orgId, CaseRelationship rel,
                                 User grantedBy, GrantScope... scopes) {
        for (GrantScope scope : scopes) {
            Grant g = new Grant();
            g.setImmigrationCase(c);
            g.setSubjectImmOrgId(orgId);
            g.setRelationship(rel);
            g.setScope(scope);
            g.setGrantedBy(grantedBy);
            grantRepo.save(g);
        }
    }
}
