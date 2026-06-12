package com.receipttracker.service;

import com.receipttracker.dto.OrganizationDTO;
import com.receipttracker.dto.PlatformStatsDTO;
import com.receipttracker.model.AppFeature;
import com.receipttracker.model.OrgFeature;
import com.receipttracker.model.OrgMembership.MemberStatus;
import com.receipttracker.model.Organization;
import com.receipttracker.model.Organization.OrgPlan;
import com.receipttracker.model.Organization.OrgStatus;
import com.receipttracker.model.User;
import com.receipttracker.repository.OrgFeatureRepository;
import com.receipttracker.repository.OrgMembershipRepository;
import com.receipttracker.repository.OrgOrderRepository;
import com.receipttracker.repository.OrganizationRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class PlatformService {

    private static final Logger log = LoggerFactory.getLogger(PlatformService.class);

    @Autowired private OrganizationRepository orgRepo;
    @Autowired private OrgMembershipRepository memberRepo;
    @Autowired private OrgOrderRepository orgOrderRepo;
    @Autowired private OrgFeatureRepository featureRepo;
    @Autowired private UserRepository userRepo;

    @Transactional(readOnly = true)
    public List<OrganizationDTO> listAllOrgs() {
        requirePlatformAdmin();
        return orgRepo.findAll().stream()
                .map(org -> {
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
                    dto.setSquareConfigured(org.isSquareConfigured());
                    dto.setSquareEnvironment(org.getSquareEnvironment() != null
                            ? org.getSquareEnvironment().name() : null);
                    long activeMembers = memberRepo.findByOrgOrderByInvitedAtDesc(org).stream()
                            .filter(m -> m.getStatus() == MemberStatus.ACTIVE).count();
                    dto.setMemberCount((int) activeMembers);
                    dto.setRecentOrderCount((int) orgOrderRepo.findByOrgOrderByPlacedAtDesc(org).stream()
                            .limit(50).count());
                    dto.setFeatures(activeFeatureNames(org));
                    return dto;
                })
                .collect(Collectors.toList());
    }

    @Transactional
    public OrganizationDTO setOrgStatus(String slug, String status) {
        requirePlatformAdmin();
        Organization org = requireOrg(slug);
        org.setStatus(OrgStatus.valueOf(status.toUpperCase()));
        orgRepo.save(org);
        log.info("Platform: org {} status set to {}", slug, status);
        return toSimpleDTO(org);
    }

    @Transactional
    public OrganizationDTO setOrgPlan(String slug, String plan) {
        requirePlatformAdmin();
        Organization org = requireOrg(slug);
        org.setPlan(OrgPlan.valueOf(plan.toUpperCase()));
        orgRepo.save(org);
        log.info("Platform: org {} plan set to {}", slug, plan);
        return toSimpleDTO(org);
    }

    @Transactional(readOnly = true)
    public PlatformStatsDTO getStats() {
        requirePlatformAdmin();
        List<Organization> all = orgRepo.findAll();
        long total     = all.size();
        long active    = all.stream().filter(o -> o.getStatus() == OrgStatus.ACTIVE).count();
        long suspended = all.stream().filter(o -> o.getStatus() == OrgStatus.SUSPENDED).count();
        long free      = all.stream().filter(o -> o.getPlan() == OrgPlan.FREE).count();
        long pro       = all.stream().filter(o -> o.getPlan() == OrgPlan.PRO).count();
        long sqConfigured = all.stream().filter(Organization::isSquareConfigured).count();
        long totalMembers = memberRepo.count();
        Map<String, Long> adoption = new LinkedHashMap<>();
        for (AppFeature f : AppFeature.values()) {
            adoption.put(f.name(), featureRepo.countByFeature(f));
        }
        return new PlatformStatsDTO(total, active, suspended, free, pro, totalMembers, sqConfigured, adoption);
    }

    // ── Feature entitlements ───────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<String> listOrgFeatures(String slug) {
        requirePlatformAdmin();
        return activeFeatureNames(requireOrg(slug));
    }

    @Transactional
    public List<String> grantFeature(String slug, String featureName, LocalDateTime expiresAt) {
        requirePlatformAdmin();
        Organization org = requireOrg(slug);
        AppFeature feature = parseFeature(featureName);
        OrgFeature row = featureRepo.findByOrgAndFeature(org, feature)
                .orElseGet(() -> {
                    OrgFeature f = new OrgFeature();
                    f.setOrg(org);
                    f.setFeature(feature);
                    return f;
                });
        row.setExpiresAt(expiresAt);
        featureRepo.save(row);
        log.info("Platform: org {} granted feature {} (expires {})", slug, feature, expiresAt);
        return activeFeatureNames(org);
    }

    @Transactional
    public List<String> revokeFeature(String slug, String featureName) {
        requirePlatformAdmin();
        Organization org = requireOrg(slug);
        AppFeature feature = parseFeature(featureName);
        featureRepo.deleteByOrgAndFeature(org, feature);
        log.info("Platform: org {} feature {} revoked", slug, feature);
        return activeFeatureNames(org);
    }

    private AppFeature parseFeature(String name) {
        if (name == null || name.isBlank()) throw new RuntimeException("feature is required");
        try {
            return AppFeature.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Unknown feature: " + name);
        }
    }

    private List<String> activeFeatureNames(Organization org) {
        return featureRepo.findByOrg(org).stream()
                .filter(OrgFeature::isActive)
                .map(f -> f.getFeature().name())
                .sorted()
                .collect(Collectors.toList());
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private void requirePlatformAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) throw new RuntimeException("Not authenticated");
        OAuth2User oAuth2User = (OAuth2User) auth.getPrincipal();
        String googleId = oAuth2User.getAttribute("sub");
        User user = userRepo.findByGoogleId(googleId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (!Boolean.TRUE.equals(user.getPlatformAdmin()))
            throw new RuntimeException("Platform admin access required");
    }

    private Organization requireOrg(String slug) {
        return orgRepo.findBySlug(slug)
                .orElseThrow(() -> new RuntimeException("Organization not found: " + slug));
    }

    private OrganizationDTO toSimpleDTO(Organization org) {
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
        return dto;
    }
}
