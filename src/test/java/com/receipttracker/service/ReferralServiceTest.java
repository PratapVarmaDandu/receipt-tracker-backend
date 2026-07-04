package com.receipttracker.service;

import com.receipttracker.dto.ReferralSummaryDTO;
import com.receipttracker.model.AppFeature;
import com.receipttracker.model.Referral;
import com.receipttracker.model.User;
import com.receipttracker.repository.ReferralRepository;
import com.receipttracker.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReferralServiceTest {

    @Mock private UserRepository userRepo;
    @Mock private ReferralRepository referralRepo;
    @Mock private UserFeatureService userFeatureService;

    @InjectMocks private ReferralService service;

    private User referrer;
    private User newUser;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "frontendUrl", "http://localhost:4200");

        referrer = new User();
        referrer.setId(1L);
        referrer.setReferralCode("ABC12345");

        newUser = new User();
        newUser.setId(2L);

        when(userRepo.findById(1L)).thenReturn(Optional.of(referrer));
        when(userRepo.findById(2L)).thenReturn(Optional.of(newUser));
    }

    @Test
    void claimGrantsBonusAndRecordsReferral() {
        when(userRepo.findByReferralCode("ABC12345")).thenReturn(Optional.of(referrer));
        when(referralRepo.existsByReferredUser(newUser)).thenReturn(false);
        when(referralRepo.countByReferrerAndRewardGrantedTrueAndCreatedAtAfter(eq(referrer), any())).thenReturn(0L);

        service.claim("abc12345", 2L);

        verify(userFeatureService).grantBonusMonths(1L, AppFeature.EXPENSE_SHARING, 1, "referral");
        ArgumentCaptor<Referral> captor = ArgumentCaptor.forClass(Referral.class);
        verify(referralRepo).save(captor.capture());
        assertThat(captor.getValue().getReferrer()).isEqualTo(referrer);
        assertThat(captor.getValue().getReferredUser()).isEqualTo(newUser);
        assertThat(captor.getValue().isRewardGranted()).isTrue();
    }

    @Test
    void rejectsSelfReferral() {
        User self = new User();
        self.setId(1L);
        self.setReferralCode("SELF0001");
        when(userRepo.findById(1L)).thenReturn(Optional.of(self));
        when(userRepo.findByReferralCode("SELF0001")).thenReturn(Optional.of(self));

        assertThatThrownBy(() -> service.claim("SELF0001", 1L))
                .hasMessageContaining("own referral code");
        verify(referralRepo, never()).save(any());
    }

    @Test
    void rejectsDoubleClaimForSameReferredUser() {
        when(userRepo.findByReferralCode("ABC12345")).thenReturn(Optional.of(referrer));
        when(referralRepo.existsByReferredUser(newUser)).thenReturn(true);

        assertThatThrownBy(() -> service.claim("ABC12345", 2L))
                .hasMessageContaining("already been credited");
        verify(userFeatureService, never()).grantBonusMonths(any(), any(), anyInt(), any());
    }

    @Test
    void rejectsUnknownCode() {
        when(userRepo.findByReferralCode("NOPE0000")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.claim("NOPE0000", 2L))
                .hasMessageContaining("Invalid referral code");
    }

    @Test
    void skipsRewardOnceAnnualCapReached() {
        when(userRepo.findByReferralCode("ABC12345")).thenReturn(Optional.of(referrer));
        when(referralRepo.existsByReferredUser(newUser)).thenReturn(false);
        when(referralRepo.countByReferrerAndRewardGrantedTrueAndCreatedAtAfter(eq(referrer), any())).thenReturn(12L);

        service.claim("ABC12345", 2L);

        verify(userFeatureService, never()).grantBonusMonths(any(), any(), anyInt(), any());
        ArgumentCaptor<Referral> captor = ArgumentCaptor.forClass(Referral.class);
        verify(referralRepo).save(captor.capture());
        assertThat(captor.getValue().isRewardGranted()).isFalse();
    }

    @Test
    void ensureReferralCodeReturnsExistingCodeWithoutRegenerating() {
        String code = service.ensureReferralCode(1L);
        assertThat(code).isEqualTo("ABC12345");
        verify(userRepo, never()).saveAndFlush(any());
    }

    @Test
    void ensureReferralCodeGeneratesForNewUser() {
        User noCodeYet = new User();
        noCodeYet.setId(3L);
        when(userRepo.findById(3L)).thenReturn(Optional.of(noCodeYet));
        when(userRepo.saveAndFlush(any())).thenReturn(noCodeYet);

        String code = service.ensureReferralCode(3L);

        assertThat(code).isNotBlank();
        assertThat(noCodeYet.getReferralCode()).isEqualTo(code);
    }

    @Test
    void getMineReturnsSummaryForUserWithCode() {
        when(referralRepo.countByReferrer(referrer)).thenReturn(5L);
        when(referralRepo.countByReferrerAndRewardGrantedTrueAndCreatedAtAfter(eq(referrer), any())).thenReturn(3L);

        ReferralSummaryDTO summary = service.getMine(1L);

        assertThat(summary.getReferralCode()).isEqualTo("ABC12345");
        assertThat(summary.getShareLink()).isEqualTo("http://localhost:4200/login?ref=ABC12345");
        assertThat(summary.getTotalReferrals()).isEqualTo(5L);
        assertThat(summary.getRewardedReferrals()).isEqualTo(3L);
        assertThat(summary.getAnnualCap()).isEqualTo(12);
    }
}
