package com.receipttracker.service;

import com.receipttracker.model.PushDeviceToken;
import com.receipttracker.model.PushPlatform;
import com.receipttracker.model.User;
import com.receipttracker.repository.PushDeviceTokenRepository;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * firebaseMessaging is never mocked here — @InjectMocks leaves it null, exactly
 * simulating "push.enabled=false or Firebase not configured" (see FirebaseConfig),
 * the same non-fatal-when-unconfigured pattern EmailService uses for JavaMailSender.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PushNotificationServiceTest {

    @Mock private PushDeviceTokenRepository tokenRepo;
    @Mock private UserRepository userRepo;

    @InjectMocks
    private PushNotificationService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(1L);
        user.setGoogleId("google-1");
        mockSecurityContext("google-1");
        when(userRepo.findByGoogleId("google-1")).thenReturn(Optional.of(user));
    }

    private void mockSecurityContext(String googleId) {
        OAuth2User principal = mock(OAuth2User.class);
        when(principal.getAttribute("sub")).thenReturn(googleId);
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(principal);
        SecurityContext ctx = mock(SecurityContext.class);
        when(ctx.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(ctx);
    }

    @Test
    void registerToken_createsNewTokenForUser() {
        when(tokenRepo.findByUserAndToken(user, "tok-abc")).thenReturn(Optional.empty());

        service.registerToken("tok-abc", "IOS");

        ArgumentCaptor<PushDeviceToken> captor = ArgumentCaptor.forClass(PushDeviceToken.class);
        verify(tokenRepo).save(captor.capture());
        assertThat(captor.getValue().getUser()).isEqualTo(user);
        assertThat(captor.getValue().getToken()).isEqualTo("tok-abc");
        assertThat(captor.getValue().getPlatform()).isEqualTo(PushPlatform.IOS);
    }

    @Test
    void registerToken_updatesExistingToken() {
        PushDeviceToken existing = new PushDeviceToken();
        existing.setId(9L);
        existing.setUser(user);
        existing.setToken("tok-abc");
        existing.setPlatform(PushPlatform.ANDROID);
        when(tokenRepo.findByUserAndToken(user, "tok-abc")).thenReturn(Optional.of(existing));

        service.registerToken("tok-abc", "IOS");

        verify(tokenRepo).save(existing);
        assertThat(existing.getPlatform()).isEqualTo(PushPlatform.IOS);
    }

    @Test
    void registerToken_invalidPlatform_throws() {
        assertThatThrownBy(() -> service.registerToken("tok-abc", "WEB"))
                .isInstanceOf(RuntimeException.class);
        verify(tokenRepo, never()).save(any());
    }

    @Test
    void registerToken_blankToken_throws() {
        assertThatThrownBy(() -> service.registerToken("", "IOS"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void unregisterToken_deletesByToken() {
        service.unregisterToken("tok-abc");
        verify(tokenRepo).deleteByToken("tok-abc");
    }

    @Test
    void sendToUser_noTokens_noops() {
        when(tokenRepo.findByUser(user)).thenReturn(Collections.emptyList());

        service.sendToUser(user, "title", "body");

        // No exception thrown, nothing else to assert — firebaseMessaging is never touched
    }

    @Test
    void sendToUser_firebaseNotConfigured_logsAndNoops() {
        PushDeviceToken token = new PushDeviceToken();
        token.setId(1L);
        token.setUser(user);
        token.setToken("tok-abc");
        token.setPlatform(PushPlatform.IOS);
        List<PushDeviceToken> tokens = List.of(token);
        when(tokenRepo.findByUser(user)).thenReturn(tokens);

        // firebaseMessaging field is null (never mocked) — must not throw
        service.sendToUser(user, "title", "body");
    }
}
