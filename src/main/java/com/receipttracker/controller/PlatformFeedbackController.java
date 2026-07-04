package com.receipttracker.controller;

import com.receipttracker.model.AppFeature;
import com.receipttracker.model.UserSubmission;
import com.receipttracker.repository.UserSubmissionRepository;
import com.receipttracker.service.PlatformService;
import com.receipttracker.service.UserFeatureService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/platform/feedback")
public class PlatformFeedbackController {

    private static final Logger log = LoggerFactory.getLogger(PlatformFeedbackController.class);

    @Autowired private UserSubmissionRepository submissionRepo;
    @Autowired private UserFeatureService userFeatureService;
    @Autowired private PlatformService platformService;

    /** Admin reviews a feedback/bug/idea submission and manually grants a bonus month. */
    @PutMapping("/{id}/grant-reward")
    public ResponseEntity<?> grantReward(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            platformService.requirePlatformAdmin();

            UserSubmission submission = submissionRepo.findById(id)
                    .orElseThrow(() -> new RuntimeException("Submission not found: " + id));
            if (submission.isRewardGranted()) {
                throw new RuntimeException("Reward already granted for this submission");
            }

            AppFeature feature = parseFeature((String) body.get("feature"));
            int months = body.get("months") != null ? ((Number) body.get("months")).intValue() : 1;
            if (months < 1) throw new RuntimeException("months must be at least 1");

            LocalDateTime expiresAt = userFeatureService.grantBonusMonths(
                    submission.getUser().getId(), feature, months,
                    "admin_review:" + submission.getType());

            submission.setRewardGranted(true);
            submissionRepo.save(submission);

            log.info("Platform: granted {} bonus month(s) of {} to user {} for submission {}",
                    months, feature, submission.getUser().getId(), id);
            return ResponseEntity.ok(Map.of(
                    "feature", feature.name(),
                    "expiresAt", expiresAt == null ? "perpetual" : expiresAt.toString()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private AppFeature parseFeature(String name) {
        if (name == null || name.isBlank()) throw new RuntimeException("feature is required");
        try {
            return AppFeature.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Unknown feature: " + name);
        }
    }
}
