package com.receipttracker.service;

import com.receipttracker.dto.*;
import com.receipttracker.model.*;
import com.receipttracker.model.OrgMembership.MemberStatus;
import com.receipttracker.model.OrgMembership.OrgRole;
import com.receipttracker.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class OrganizationService {

    private static final Logger log = LoggerFactory.getLogger(OrganizationService.class);

    @Autowired private OrganizationRepository orgRepo;
    @Autowired private OrgMembershipRepository memberRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private EmailService emailService;
    @Autowired private EncryptionService encryptionService;
    @Autowired private OrgOrderRepository orgOrderRepo;
    @Autowired private CloverApiService cloverApiService;
    @Autowired private FeatureEntitlementService entitlement;

    @Value("${app.frontend.url:http://localhost:4200}")
    private String frontendUrl;

    // ── Create ─────────────────────────────────────────────────────────────────

    @Transactional
    public OrganizationDTO create(CreateOrganizationRequest req) {
        User owner = currentUser();
        String slug = slugify(req.getSlug() != null ? req.getSlug() : req.getName());

        if (orgRepo.existsBySlug(slug)) {
            throw new RuntimeException("An organization with that URL already exists. Try a different name.");
        }

        Organization org = new Organization();
        org.setName(req.getName().trim());
        org.setSlug(slug);
        org.setOwner(owner);
        org = orgRepo.save(org);

        OrgMembership ownerMembership = new OrgMembership();
        ownerMembership.setOrg(org);
        ownerMembership.setUser(owner);
        ownerMembership.setInviteEmail(owner.getEmail());
        ownerMembership.setRole(OrgRole.OWNER);
        ownerMembership.setStatus(MemberStatus.ACTIVE);
        ownerMembership.setJoinedAt(LocalDateTime.now());
        memberRepo.save(ownerMembership);

        log.info("Organization created: slug={} owner={}", slug, owner.getEmail());
        return toDTO(org, OrgRole.OWNER);
    }

    // ── Read ───────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<OrganizationDTO> listMine() {
        User me = currentUser();
        List<OrgMembership> memberships = memberRepo.findByUserAndStatusNot(me, MemberStatus.REVOKED);
        return memberships.stream()
                .map(m -> toDTO(m.getOrg(), m.getRole()))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public OrganizationDTO getBySlug(String slug) {
        Organization org = requireOrg(slug);
        User me = currentUser();
        OrgRole myRole = getMyRole(org, me);
        return toDTO(org, myRole);
    }

    // ── Update ─────────────────────────────────────────────────────────────────

    @Transactional
    public OrganizationDTO update(String slug, CreateOrganizationRequest req) {
        Organization org = requireOrg(slug);
        requireAtLeast(org, OrgRole.OWNER);

        String newName = req.getName() != null ? req.getName().trim() : null;
        String newSlug = req.getSlug() != null ? req.getSlug().trim() : null;

        if (newName == null || newName.isBlank())
            throw new RuntimeException("Organization name is required");

        if (newSlug != null && !newSlug.isBlank()) {
            newSlug = slugify(newSlug);
            if (!newSlug.equals(slug) && orgRepo.existsBySlug(newSlug))
                throw new RuntimeException("An organization with that URL already exists");
            org.setSlug(newSlug);
        }

        org.setName(newName);
        orgRepo.saveAndFlush(org);
        // Re-load to guarantee the returned DTO reflects actual DB state (especially Square fields)
        org = orgRepo.findBySlug(org.getSlug()).orElseThrow();
        log.info("Organization updated: slug={} by={}", org.getSlug(), currentUser().getEmail());
        return toDTO(org, OrgRole.OWNER);
    }

    // ── Members ────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<OrgMemberDTO> listMembers(String slug) {
        Organization org = requireOrg(slug);
        requireAtLeast(org, OrgRole.VIEWER);
        return memberRepo.findByOrgOrderByInvitedAtDesc(org)
                .stream().map(this::toMemberDTO).collect(Collectors.toList());
    }

    @Transactional
    public OrgMemberDTO invite(String slug, InviteMemberRequest req) {
        Organization org = requireOrg(slug);
        requireAtLeast(org, OrgRole.ADMIN);

        String email = req.getEmail().trim().toLowerCase();
        OrgRole role = parseRole(req.getRole());

        if (role == OrgRole.OWNER) throw new RuntimeException("Cannot invite as OWNER");
        if (org.getOwner().getEmail().equalsIgnoreCase(email)) {
            throw new RuntimeException("Cannot invite the organization owner");
        }
        if (memberRepo.existsByOrgAndInviteEmailAndStatusNot(org, email, MemberStatus.REVOKED)) {
            throw new RuntimeException("An active invite already exists for " + email);
        }

        // Reuse the existing row if the previous invite was revoked (unique constraint prevents a new insert)
        OrgMembership m = memberRepo.findByOrgAndInviteEmail(org, email)
                .orElseGet(OrgMembership::new);
        m.setOrg(org);
        m.setInviteEmail(email);
        m.setRole(role);
        m.setStatus(MemberStatus.PENDING);
        m.setUser(null);
        m.setJoinedAt(null);
        m.setInviteToken(UUID.randomUUID().toString());
        m.setInvitedAt(LocalDateTime.now());
        m = memberRepo.save(m);

        String inviteUrl = frontendUrl + "/admin/join/" + m.getInviteToken();
        emailService.sendOrgInvite(email, org.getOwner().getName(), org.getName(), role.name(), inviteUrl);
        log.info("Org invite sent: org={} to={} role={}", slug, email, role);
        return toMemberDTO(m);
    }

    @Transactional
    public OrgMemberDTO acceptInvite(String token) {
        User me = currentUser();
        OrgMembership m = memberRepo.findByInviteToken(token)
                .orElseThrow(() -> new RuntimeException("Invite not found"));
        if (m.getStatus() == MemberStatus.REVOKED) throw new RuntimeException("This invite has been revoked");
        if (m.getStatus() == MemberStatus.ACTIVE)  return toMemberDTO(m); // idempotent
        if (!me.getEmail().equalsIgnoreCase(m.getInviteEmail())) {
            throw new RuntimeException("This invite is not for your account (" + me.getEmail() + ")");
        }
        m.setUser(me);
        m.setStatus(MemberStatus.ACTIVE);
        m.setJoinedAt(LocalDateTime.now());
        log.info("Org invite accepted: org={} user={}", m.getOrg().getSlug(), me.getEmail());
        return toMemberDTO(memberRepo.save(m));
    }

    @Transactional(readOnly = true)
    public OrgMemberDTO getInviteByToken(String token) {
        OrgMembership m = memberRepo.findByInviteToken(token)
                .orElseThrow(() -> new RuntimeException("Invite not found"));
        if (m.getStatus() == MemberStatus.REVOKED) throw new RuntimeException("This invite has been revoked");
        return toMemberDTO(m);
    }

    @Transactional
    public void revokeMember(String slug, Long memberId) {
        Organization org = requireOrg(slug);
        requireAtLeast(org, OrgRole.ADMIN);
        OrgMembership m = memberRepo.findById(memberId)
                .orElseThrow(() -> new RuntimeException("Member not found"));
        if (!m.getOrg().getId().equals(org.getId())) throw new RuntimeException("Access denied");
        if (m.getRole() == OrgRole.OWNER) throw new RuntimeException("Cannot revoke the owner");
        m.setStatus(MemberStatus.REVOKED);
        memberRepo.save(m);
        log.info("Org member revoked: org={} memberId={}", slug, memberId);
    }

    // ── Delete ─────────────────────────────────────────────────────────────────

    @Transactional
    public void delete(String slug) {
        Organization org = requireOrg(slug);
        requireAtLeast(org, OrgRole.OWNER);
        // Remove child rows that have no cascade (receipts are left intact — they belong to users)
        orgOrderRepo.deleteAll(orgOrderRepo.findByOrgOrderByPlacedAtDesc(org));
        memberRepo.deleteAll(memberRepo.findByOrgOrderByInvitedAtDesc(org));
        orgRepo.delete(org);
        log.info("Organization deleted: slug={} by={}", slug, currentUser().getEmail());
    }

    // ── Square credentials ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public OrgSquareConfigDTO getSquareConfig(String slug) {
        Organization org = requireOrg(slug);
        requireAtLeast(org, OrgRole.VIEWER);
        return toSquareConfigDTO(org);
    }

    @Transactional
    public OrgSquareConfigDTO saveSquareConfig(String slug, OrgSquareConfigRequest req) {
        Organization org = requireOrg(slug);
        requireAtLeast(org, OrgRole.ADMIN);

        if (req.getAccessToken() == null || req.getAccessToken().isBlank())
            throw new RuntimeException("Access token is required");
        if (req.getApplicationId() == null || req.getApplicationId().isBlank())
            throw new RuntimeException("Application ID is required");
        if (req.getLocationId() == null || req.getLocationId().isBlank())
            throw new RuntimeException("Location ID is required");

        org.setSquareAccessTokenEnc(encryptionService.encrypt(req.getAccessToken().trim()));
        org.setSquareApplicationId(req.getApplicationId().trim());
        org.setSquareLocationId(req.getLocationId().trim());
        org.setSquareEnvironment(parseSquareEnv(req.getEnvironment()));
        orgRepo.save(org);

        log.info("Square config saved: org={}", slug);
        return toSquareConfigDTO(org);
    }

    @Transactional
    public void clearSquareConfig(String slug) {
        Organization org = requireOrg(slug);
        requireAtLeast(org, OrgRole.OWNER);
        org.setSquareAccessTokenEnc(null);
        org.setSquareApplicationId(null);
        org.setSquareLocationId(null);
        org.setSquareEnvironment(Organization.SquareEnv.SANDBOX);
        orgRepo.save(org);
        log.info("Square config cleared: org={}", slug);
    }

    // ── Clover credentials ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public OrgCloverConfigDTO getCloverConfig(String slug) {
        Organization org = requireOrg(slug);
        requireAtLeast(org, OrgRole.VIEWER);
        return toCloverConfigDTO(org);
    }

    @Transactional
    public OrgCloverConfigDTO saveCloverConfig(String slug, OrgCloverConfigRequest req) {
        Organization org = requireOrg(slug);
        requireAtLeast(org, OrgRole.ADMIN);

        if (req.getAccessToken() == null || req.getAccessToken().isBlank())
            throw new RuntimeException("Access token is required");
        if (req.getMerchantId() == null || req.getMerchantId().isBlank())
            throw new RuntimeException("Merchant ID is required");

        org.setCloverAccessTokenEnc(encryptionService.encrypt(req.getAccessToken().trim()));
        org.setCloverMerchantId(req.getMerchantId().trim());
        org.setCloverPublicKey(req.getPublicKey() != null ? req.getPublicKey().trim() : null);
        org.setCloverEnvironment(parseCloverEnv(req.getEnvironment()));
        orgRepo.save(org);

        log.info("Clover config saved: org={}", slug);
        return toCloverConfigDTO(org);
    }

    @Transactional
    public void clearCloverConfig(String slug) {
        Organization org = requireOrg(slug);
        requireAtLeast(org, OrgRole.OWNER);
        org.setCloverAccessTokenEnc(null);
        org.setCloverMerchantId(null);
        org.setCloverPublicKey(null);
        org.setCloverEnvironment(Organization.CloverEnv.SANDBOX);
        orgRepo.save(org);
        log.info("Clover config cleared: org={}", slug);
    }

    // ── Public store toggle ────────────────────────────────────────────────────

    @Transactional
    public OrganizationDTO setPublicStore(String slug, boolean enabled) {
        User caller = currentUser();
        Organization org = requireOrg(slug);
        boolean isPlatformAdmin = Boolean.TRUE.equals(caller.getPlatformAdmin());
        OrgMembership myMembership = memberRepo.findByOrgAndUser(org, caller).orElse(null);
        boolean isOwner = myMembership != null
                && myMembership.getStatus() == MemberStatus.ACTIVE
                && myMembership.getRole() == OrgRole.OWNER;
        if (!isOwner && !isPlatformAdmin)
            throw new RuntimeException("Only the org owner or a platform admin can change public store visibility");
        org.setPublicStore(enabled);
        orgRepo.save(org);
        log.info("Public store {} for org={} by {}", enabled ? "enabled" : "disabled", slug, caller.getEmail());
        OrgRole role = myMembership != null ? myMembership.getRole() : (isPlatformAdmin ? OrgRole.VIEWER : null);
        return toDTO(org, role);
    }

    /** Resolves decrypted Square credentials for a public store — no membership check. */
    public SquareApiService.SquareCreds resolvePublicSquareCreds(String slug) {
        Organization org = requireOrg(slug);
        if (!org.isPublicStore())
            throw new RuntimeException("This store is not publicly accessible");
        // publicStore flag IS the authorization — no org feature grant required
        if (!org.isSquareConfigured())
            throw new RuntimeException("Square is not configured for this organization");
        String token = encryptionService.decrypt(org.getSquareAccessTokenEnc());
        return new SquareApiService.SquareCreds(
                token,
                org.getSquareEnvironment() != null ? org.getSquareEnvironment().name().toLowerCase() : "sandbox",
                org.getSquareApplicationId(),
                org.getSquareLocationId()
        );
    }

    /** Resolves decrypted Clover credentials for a public store — no membership check. */
    public CloverApiService.CloverCreds resolvePublicCloverCreds(String slug) {
        Organization org = requireOrg(slug);
        if (!org.isPublicStore())
            throw new RuntimeException("This store is not publicly accessible");
        // publicStore flag IS the authorization — no org feature grant required
        if (!org.isCloverConfigured())
            throw new RuntimeException("Clover is not configured for this organization");
        String token = encryptionService.decrypt(org.getCloverAccessTokenEnc());
        return new CloverApiService.CloverCreds(
                token,
                org.getCloverEnvironment() != null ? org.getCloverEnvironment().name() : "SANDBOX",
                org.getCloverMerchantId()
        );
    }

    /** Resolves decrypted Clover credentials for API calls. Throws if not configured
     *  or the org lacks the SHOP_POS feature. */
    public CloverApiService.CloverCreds resolveCloverCreds(String slug) {
        Organization org = requireOrg(slug);
        requireAtLeast(org, OrgRole.STAFF);
        entitlement.requireOrgFeature(org, AppFeature.SHOP_POS);
        if (!org.isCloverConfigured())
            throw new RuntimeException("Clover is not configured for this organization");
        String token = encryptionService.decrypt(org.getCloverAccessTokenEnc());
        return new CloverApiService.CloverCreds(
                token,
                org.getCloverEnvironment() != null ? org.getCloverEnvironment().name() : "SANDBOX",
                org.getCloverMerchantId()
        );
    }

    private OrgCloverConfigDTO toCloverConfigDTO(Organization org) {
        return new OrgCloverConfigDTO(
                org.getCloverMerchantId(),
                org.getCloverPublicKey(),
                org.getCloverEnvironment() != null ? org.getCloverEnvironment().name() : null,
                org.isCloverConfigured()
        );
    }

    private Organization.CloverEnv parseCloverEnv(String env) {
        if ("PRODUCTION".equalsIgnoreCase(env)) return Organization.CloverEnv.PRODUCTION;
        return Organization.CloverEnv.SANDBOX;
    }

    /** Resolves decrypted Square credentials for API calls. Throws if not configured
     *  or the org lacks the SHOP_POS feature. */
    public SquareApiService.SquareCreds resolveSquareCreds(String slug) {
        Organization org = requireOrg(slug);
        requireAtLeast(org, OrgRole.STAFF);
        entitlement.requireOrgFeature(org, AppFeature.SHOP_POS);
        if (!org.isSquareConfigured())
            throw new RuntimeException("Square is not configured for this organization");
        String token = encryptionService.decrypt(org.getSquareAccessTokenEnc());
        return new SquareApiService.SquareCreds(
                token,
                org.getSquareEnvironment() != null ? org.getSquareEnvironment().name().toLowerCase() : "sandbox",
                org.getSquareApplicationId(),
                org.getSquareLocationId()
        );
    }

    private OrgSquareConfigDTO toSquareConfigDTO(Organization org) {
        return new OrgSquareConfigDTO(
                org.getSquareApplicationId(),
                org.getSquareLocationId(),
                org.getSquareEnvironment() != null ? org.getSquareEnvironment().name() : null,
                org.isSquareConfigured()
        );
    }

    private Organization.SquareEnv parseSquareEnv(String env) {
        if ("PRODUCTION".equalsIgnoreCase(env)) return Organization.SquareEnv.PRODUCTION;
        return Organization.SquareEnv.SANDBOX;
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Organization requireOrg(String slug) {
        return orgRepo.findBySlug(slug)
                .orElseThrow(() -> new RuntimeException("Organization not found: " + slug));
    }

    private OrgRole getMyRole(Organization org, User me) {
        return memberRepo.findByOrgAndUser(org, me)
                .filter(m -> m.getStatus() == MemberStatus.ACTIVE)
                .map(OrgMembership::getRole)
                .orElseThrow(() -> new RuntimeException("You are not a member of this organization"));
    }

    private void requireAtLeast(Organization org, OrgRole minimum) {
        OrgRole myRole = getMyRole(org, currentUser());
        if (roleLevel(myRole) < roleLevel(minimum)) {
            throw new RuntimeException("Insufficient permissions — requires " + minimum);
        }
    }

    private int roleLevel(OrgRole role) {
        return switch (role) {
            case OWNER  -> 4;
            case ADMIN  -> 3;
            case STAFF  -> 2;
            case VIEWER -> 1;
        };
    }

    private OrgRole parseRole(String role) {
        try { return OrgRole.valueOf(role.toUpperCase()); }
        catch (Exception e) { throw new RuntimeException("Invalid role: " + role); }
    }

    private String slugify(String input) {
        return input.trim().toLowerCase()
                .replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("[\\s]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
    }

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) throw new RuntimeException("Not authenticated");
        Object principal = auth.getPrincipal();
        String email;
        if (principal instanceof OAuth2User oauth) {
            email = oauth.getAttribute("email");
        } else {
            email = principal.toString();
        }
        return userRepo.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found: " + email));
    }

    private OrganizationDTO toDTO(Organization org, OrgRole myRole) {
        OrganizationDTO dto = new OrganizationDTO();
        dto.setId(org.getId());
        dto.setName(org.getName());
        dto.setSlug(org.getSlug());
        dto.setPlan(org.getPlan().name());
        dto.setStatus(org.getStatus().name());
        dto.setOwnerId(org.getOwner().getId());
        dto.setOwnerName(org.getOwner().getName());
        dto.setOwnerEmail(org.getOwner().getEmail());
        dto.setCreatedAt(org.getCreatedAt());
        dto.setMyRole(myRole != null ? myRole.name() : null);
        long count = memberRepo.findByOrgOrderByInvitedAtDesc(org).stream()
                .filter(m -> m.getStatus() == MemberStatus.ACTIVE).count();
        dto.setMemberCount((int) count);
        dto.setSquareConfigured(org.isSquareConfigured());
        dto.setSquareEnvironment(org.getSquareEnvironment() != null ? org.getSquareEnvironment().name() : null);
        dto.setCloverConfigured(org.isCloverConfigured());
        dto.setCloverEnvironment(org.getCloverEnvironment() != null ? org.getCloverEnvironment().name() : null);
        dto.setRecentOrderCount(orgOrderRepo.findByOrgOrderByPlacedAtDesc(org, PageRequest.of(0, 50)).size());
        dto.setPublicStore(org.isPublicStore());
        return dto;
    }

    private OrgMemberDTO toMemberDTO(OrgMembership m) {
        OrgMemberDTO dto = new OrgMemberDTO();
        dto.setId(m.getId());
        dto.setInviteEmail(m.getInviteEmail());
        dto.setRole(m.getRole().name());
        dto.setStatus(m.getStatus().name());
        dto.setInvitedAt(m.getInvitedAt());
        dto.setJoinedAt(m.getJoinedAt());
        if (m.getUser() != null) {
            dto.setUserName(m.getUser().getName());
            dto.setUserPicture(m.getUser().getPicture());
        }
        return dto;
    }
}
