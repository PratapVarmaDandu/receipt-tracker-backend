package com.receipttracker.service;

import com.receipttracker.model.AppFeature;
import com.receipttracker.model.User;
import com.receipttracker.model.UserFeatureGrant;
import com.receipttracker.repository.UserFeatureRepository;
import com.receipttracker.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class UserFeatureService {

    private static final Logger log = LoggerFactory.getLogger(UserFeatureService.class);

    @Autowired private UserFeatureRepository userFeatureRepo;
    @Autowired private UserRepository userRepo;

    /** Grants feature to user — idempotent: updates expiresAt if row already exists. */
    @Transactional
    public void grantFeature(Long userId, AppFeature feature) {
        User user = requireUser(userId);
        UserFeatureGrant grant = userFeatureRepo.findByUserAndFeature(user, feature)
                .orElseGet(() -> {
                    UserFeatureGrant g = new UserFeatureGrant();
                    g.setUser(user);
                    g.setFeature(feature);
                    return g;
                });
        grant.setExpiresAt(null); // perpetual grant
        userFeatureRepo.save(grant);
        log.info("UserFeatureService: granted {} to user {}", feature, userId);
    }

    /**
     * Extends (or creates) a bonus grant for {feature} by {months}, stacking on top of
     * whatever non-expired expiry already exists (12 -> 13, 13 -> 14). A no-op if the
     * user already has a perpetual grant for this feature (active paid subscription --
     * grantFeature() never sets a finite expiresAt, so there's nothing to extend and
     * creating a finite one here would shorten their access, not add to it). Returns
     * the resulting expiresAt, or null if skipped because the grant is perpetual.
     */
    @Transactional
    public LocalDateTime grantBonusMonths(Long userId, AppFeature feature, int months, String reason) {
        User user = requireUser(userId);
        UserFeatureGrant grant = userFeatureRepo.findByUserAndFeature(user, feature)
                .orElseGet(() -> {
                    UserFeatureGrant g = new UserFeatureGrant();
                    g.setUser(user);
                    g.setFeature(feature);
                    return g;
                });

        boolean alreadyPerpetual = grant.getId() != null && grant.getExpiresAt() == null;
        if (alreadyPerpetual) {
            log.info("UserFeatureService: bonus skipped for user {} feature {} — already perpetual (reason={})",
                    userId, feature, reason);
            return null;
        }

        LocalDateTime base = (grant.getExpiresAt() != null && grant.getExpiresAt().isAfter(LocalDateTime.now()))
                ? grant.getExpiresAt() : LocalDateTime.now();
        grant.setExpiresAt(base.plusMonths(months));
        userFeatureRepo.save(grant);
        log.info("UserFeatureService: granted {} bonus month(s) of {} to user {} (reason={}), new expiry={}",
                months, feature, userId, reason, grant.getExpiresAt());
        return grant.getExpiresAt();
    }

    @Transactional
    public void revokeFeature(Long userId, AppFeature feature) {
        User user = requireUser(userId);
        userFeatureRepo.deleteByUserAndFeature(user, feature);
        log.info("UserFeatureService: revoked {} from user {}", feature, userId);
    }

    @Transactional(readOnly = true)
    public List<String> getUserFeatures(Long userId) {
        User user = requireUser(userId);
        return userFeatureRepo.findByUser(user).stream()
                .filter(UserFeatureGrant::isActive)
                .map(g -> g.getFeature().name())
                .sorted()
                .collect(Collectors.toList());
    }

    private User requireUser(Long userId) {
        return userRepo.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));
    }
}
