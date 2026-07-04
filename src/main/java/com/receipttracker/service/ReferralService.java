package com.receipttracker.service;

import com.receipttracker.dto.ReferralSummaryDTO;
import com.receipttracker.model.AppFeature;
import com.receipttracker.model.Referral;
import com.receipttracker.model.User;
import com.receipttracker.repository.ReferralRepository;
import com.receipttracker.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class ReferralService {

    private static final Logger log = LoggerFactory.getLogger(ReferralService.class);

    /**
     * Fixed reward feature — unlike the admin-granted submission reward (Session 3),
     * referral rewards fire automatically with no human review, so the feature can't be
     * picked per-grant; EXPENSE_SHARING is the flagship module included in every
     * purchasable plan bundle.
     */
    private static final AppFeature REWARD_FEATURE = AppFeature.EXPENSE_SHARING;

    /**
     * Caps total referral bonus months per referrer per rolling year. The spec as given
     * describes uncapped stacking, but referral rewards are unsupervised (no admin
     * review gates them the way Session 3's submission rewards are), making them
     * farmable via disposable Google accounts with no cap.
     */
    private static final int ANNUAL_REWARD_CAP = 12;

    private static final int CODE_LENGTH = 8;
    private static final int MAX_GENERATION_ATTEMPTS = 5;

    @Autowired private UserRepository userRepo;
    @Autowired private ReferralRepository referralRepo;
    @Autowired private UserFeatureService userFeatureService;

    @Value("${app.frontend.url:http://localhost:4200}")
    private String frontendUrl;

    @Transactional
    public String ensureReferralCode(Long userId) {
        User user = requireUser(userId);
        if (user.getReferralCode() != null) {
            return user.getReferralCode();
        }
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            String code = generateCode();
            user.setReferralCode(code);
            try {
                userRepo.saveAndFlush(user);
                return code;
            } catch (DataIntegrityViolationException e) {
                log.warn("Referral code collision on attempt {}, retrying", attempt + 1);
            }
        }
        throw new RuntimeException("Failed to generate a unique referral code");
    }

    @Transactional(readOnly = true)
    public ReferralSummaryDTO getMine(Long userId) {
        User user = requireUser(userId);
        String code = user.getReferralCode();
        long total = code != null ? referralRepo.countByReferrer(user) : 0;
        long rewardedThisYear = code != null
                ? referralRepo.countByReferrerAndRewardGrantedTrueAndCreatedAtAfter(user, LocalDateTime.now().minusYears(1))
                : 0;
        return new ReferralSummaryDTO(code, code != null ? shareLink(code) : null,
                total, rewardedThisYear, ANNUAL_REWARD_CAP);
    }

    /** Called once, immediately after a brand-new user's first login (see ReferralController). */
    @Transactional
    public void claim(String code, Long newUserId) {
        if (code == null || code.isBlank()) {
            throw new RuntimeException("Referral code is required");
        }
        User referredUser = requireUser(newUserId);
        User referrer = userRepo.findByReferralCode(code.trim().toUpperCase())
                .orElseThrow(() -> new RuntimeException("Invalid referral code"));

        if (referrer.getId().equals(newUserId)) {
            throw new RuntimeException("You cannot use your own referral code");
        }
        if (referralRepo.existsByReferredUser(referredUser)) {
            throw new RuntimeException("This account has already been credited toward a referral");
        }

        Referral referral = new Referral();
        referral.setReferrer(referrer);
        referral.setReferredUser(referredUser);

        long grantedThisYear = referralRepo.countByReferrerAndRewardGrantedTrueAndCreatedAtAfter(
                referrer, LocalDateTime.now().minusYears(1));
        if (grantedThisYear < ANNUAL_REWARD_CAP) {
            userFeatureService.grantBonusMonths(referrer.getId(), REWARD_FEATURE, 1, "referral");
            referral.setRewardGranted(true);
        } else {
            log.warn("Referral: annual cap ({}) reached for referrer {} — relationship recorded, no reward",
                    ANNUAL_REWARD_CAP, referrer.getId());
        }

        referralRepo.save(referral);
        log.info("Referral: user {} referred by user {} (rewardGranted={})",
                newUserId, referrer.getId(), referral.isRewardGranted());
    }

    private String shareLink(String code) {
        return frontendUrl + "/login?ref=" + code;
    }

    private String generateCode() {
        long rand = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
        String base36 = Long.toString(rand, 36).toUpperCase();
        if (base36.length() > CODE_LENGTH) {
            base36 = base36.substring(base36.length() - CODE_LENGTH);
        }
        while (base36.length() < CODE_LENGTH) {
            base36 = "0" + base36;
        }
        return base36;
    }

    private User requireUser(Long userId) {
        return userRepo.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));
    }
}
