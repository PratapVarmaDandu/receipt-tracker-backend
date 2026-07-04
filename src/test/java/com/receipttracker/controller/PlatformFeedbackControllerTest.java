package com.receipttracker.controller;

import com.receipttracker.model.AppFeature;
import com.receipttracker.model.SubmissionType;
import com.receipttracker.model.User;
import com.receipttracker.model.UserSubmission;
import com.receipttracker.repository.UserSubmissionRepository;
import com.receipttracker.service.PlatformService;
import com.receipttracker.service.UserFeatureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlatformFeedbackControllerTest {

    @Mock private UserSubmissionRepository submissionRepo;
    @Mock private UserFeatureService userFeatureService;
    @Mock private PlatformService platformService;

    @InjectMocks private PlatformFeedbackController controller;

    private UserSubmission submission;
    private User submitter;

    @BeforeEach
    void setUp() {
        submitter = new User();
        submitter.setId(42L);

        submission = new UserSubmission();
        submission.setId(1L);
        submission.setUser(submitter);
        submission.setType(SubmissionType.IDEA);
        submission.setRewardGranted(false);

        when(submissionRepo.findById(1L)).thenReturn(Optional.of(submission));
    }

    @Test
    void grantsRewardAndMarksSubmission() {
        LocalDateTime expiry = LocalDateTime.now().plusMonths(1);
        when(userFeatureService.grantBonusMonths(42L, AppFeature.GARAGE, 1, "admin_review:IDEA"))
                .thenReturn(expiry);

        ResponseEntity<?> response = controller.grantReward(1L, Map.of("feature", "GARAGE"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(submission.isRewardGranted()).isTrue();
        verify(submissionRepo).save(submission);
    }

    @Test
    void defaultsToOneMonthWhenNotSpecified() {
        controller.grantReward(1L, Map.of("feature", "GARAGE"));
        verify(userFeatureService).grantBonusMonths(42L, AppFeature.GARAGE, 1, "admin_review:IDEA");
    }

    @Test
    void honorsExplicitMonths() {
        controller.grantReward(1L, Map.of("feature", "GARAGE", "months", 3));
        verify(userFeatureService).grantBonusMonths(42L, AppFeature.GARAGE, 3, "admin_review:IDEA");
    }

    @Test
    void rejectsDoubleGrantOnSameSubmission() {
        submission.setRewardGranted(true);

        ResponseEntity<?> response = controller.grantReward(1L, Map.of("feature", "GARAGE"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(((Map<?, ?>) response.getBody()).get("error")).isEqualTo("Reward already granted for this submission");
        verify(userFeatureService, never()).grantBonusMonths(any(), any(), anyInt(), any());
    }

    @Test
    void rejectsUnknownFeature() {
        ResponseEntity<?> response = controller.grantReward(1L, Map.of("feature", "NOT_REAL"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(userFeatureService, never()).grantBonusMonths(any(), any(), anyInt(), any());
    }

    @Test
    void rejectsMissingSubmission() {
        when(submissionRepo.findById(99L)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.grantReward(99L, Map.of("feature", "GARAGE"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void nonAdminRejected() {
        doThrow(new RuntimeException("Platform admin access required"))
                .when(platformService).requirePlatformAdmin();

        ResponseEntity<?> response = controller.grantReward(1L, Map.of("feature", "GARAGE"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(userFeatureService, never()).grantBonusMonths(any(), any(), anyInt(), any());
    }
}
