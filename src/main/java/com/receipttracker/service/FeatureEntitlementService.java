package com.receipttracker.service;

import com.receipttracker.model.AppFeature;
import com.receipttracker.model.OrgFeature;
import com.receipttracker.model.OrgMembership;
import com.receipttracker.model.OrgMembership.MemberStatus;
import com.receipttracker.model.Organization;
import com.receipttracker.model.User;
import com.receipttracker.repository.OrgFeatureRepository;
import com.receipttracker.repository.OrgMembershipRepository;
import com.receipttracker.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Set;

/**
 * Per-org feature entitlements. A user has a feature when at least one of
 * their ACTIVE org memberships belongs to an org with a non-expired
 * OrgFeature grant. With features.gating.enabled=false (local profile)
 * every feature is enabled — keeps local dev usable with the mock user.
 */
@Service
public class FeatureEntitlementService {

    private static final Logger log = LoggerFactory.getLogger(FeatureEntitlementService.class);

    @Autowired private OrgFeatureRepository featureRepo;
    @Autowired private OrgMembershipRepository memberRepo;
    @Autowired private UserRepository userRepo;

    @Value("${features.gating.enabled:true}")
    private boolean gatingEnabled;

    @Transactional(readOnly = true)
    public Set<AppFeature> getMyFeatures() {
        if (!gatingEnabled) return EnumSet.allOf(AppFeature.class);
        User user = currentUser();

        // Platform admins have unrestricted access to everything
        if (Boolean.TRUE.equals(user.getPlatformAdmin())) return EnumSet.allOf(AppFeature.class);

        // Personal features are included for every logged-in user — no org required
        Set<AppFeature> features = EnumSet.of(
                AppFeature.GARAGE,
                AppFeature.DOCUMENT_VAULT,
                AppFeature.JOB_TRACKER,
                AppFeature.EXPENSE_SHARING
        );

        // Org-scoped features (e.g. SHOP_POS) still require an active org grant
        for (OrgMembership m : memberRepo.findByUserAndStatus(user, MemberStatus.ACTIVE)) {
            featureRepo.findByOrg(m.getOrg()).stream()
                    .filter(OrgFeature::isActive)
                    .forEach(f -> features.add(f.getFeature()));
        }
        return features;
    }

    @Transactional(readOnly = true)
    public void requireFeature(AppFeature feature) {
        if (!gatingEnabled) return;
        if (!getMyFeatures().contains(feature)) {
            log.warn("!!! Feature {} locked for current user", feature);
            throw new RuntimeException("FEATURE_LOCKED: " + feature.name());
        }
    }

    @Transactional(readOnly = true)
    public void requireOrgFeature(Organization org, AppFeature feature) {
        if (!gatingEnabled) return;
        boolean active = featureRepo.findByOrgAndFeature(org, feature)
                .map(OrgFeature::isActive)
                .orElse(false);
        if (!active) {
            log.warn("!!! Feature {} locked for org {}", feature, org.getSlug());
            throw new RuntimeException("FEATURE_LOCKED: " + feature.name());
        }
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
}
