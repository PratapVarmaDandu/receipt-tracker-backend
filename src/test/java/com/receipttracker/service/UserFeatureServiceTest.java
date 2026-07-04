package com.receipttracker.service;

import com.receipttracker.model.AppFeature;
import com.receipttracker.model.User;
import com.receipttracker.model.UserFeatureGrant;
import com.receipttracker.repository.UserFeatureRepository;
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

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserFeatureServiceTest {

    @Mock private UserFeatureRepository userFeatureRepo;
    @Mock private UserRepository userRepo;

    @InjectMocks private UserFeatureService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(1L);
        when(userRepo.findById(1L)).thenReturn(Optional.of(user));
    }

    @Test
    void createsFreshGrantWhenNoneExists() {
        when(userFeatureRepo.findByUserAndFeature(user, AppFeature.GARAGE)).thenReturn(Optional.empty());

        LocalDateTime before = LocalDateTime.now();
        LocalDateTime result = service.grantBonusMonths(1L, AppFeature.GARAGE, 1, "idea_submission");

        ArgumentCaptor<UserFeatureGrant> captor = ArgumentCaptor.forClass(UserFeatureGrant.class);
        verify(userFeatureRepo).save(captor.capture());
        assertThat(captor.getValue().getFeature()).isEqualTo(AppFeature.GARAGE);
        assertThat(result).isAfter(before.plusMonths(1).minusMinutes(1));
    }

    @Test
    void stacksOnTopOfActiveExpiry() {
        LocalDateTime currentExpiry = LocalDateTime.now().plusMonths(12);
        UserFeatureGrant existing = new UserFeatureGrant();
        existing.setId(5L);
        existing.setUser(user);
        existing.setFeature(AppFeature.JOB_TRACKER);
        existing.setExpiresAt(currentExpiry);
        when(userFeatureRepo.findByUserAndFeature(user, AppFeature.JOB_TRACKER)).thenReturn(Optional.of(existing));

        LocalDateTime result = service.grantBonusMonths(1L, AppFeature.JOB_TRACKER, 1, "referral");

        assertThat(result).isEqualTo(currentExpiry.plusMonths(1));
        verify(userFeatureRepo).save(existing);
    }

    @Test
    void reactivatesFromNowWhenExpired() {
        UserFeatureGrant expired = new UserFeatureGrant();
        expired.setId(6L);
        expired.setUser(user);
        expired.setFeature(AppFeature.DOCUMENT_VAULT);
        expired.setExpiresAt(LocalDateTime.now().minusDays(5));
        when(userFeatureRepo.findByUserAndFeature(user, AppFeature.DOCUMENT_VAULT)).thenReturn(Optional.of(expired));

        LocalDateTime before = LocalDateTime.now();
        LocalDateTime result = service.grantBonusMonths(1L, AppFeature.DOCUMENT_VAULT, 1, "admin_review:IDEA");

        assertThat(result).isAfter(before.plusMonths(1).minusMinutes(1));
    }

    @Test
    void skipsWhenAlreadyPerpetual() {
        UserFeatureGrant perpetual = new UserFeatureGrant();
        perpetual.setId(7L);
        perpetual.setUser(user);
        perpetual.setFeature(AppFeature.EXPENSE_SHARING);
        perpetual.setExpiresAt(null);
        when(userFeatureRepo.findByUserAndFeature(user, AppFeature.EXPENSE_SHARING)).thenReturn(Optional.of(perpetual));

        LocalDateTime result = service.grantBonusMonths(1L, AppFeature.EXPENSE_SHARING, 1, "referral");

        assertThat(result).isNull();
        verify(userFeatureRepo, never()).save(any());
    }
}
