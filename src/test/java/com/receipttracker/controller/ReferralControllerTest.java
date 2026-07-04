package com.receipttracker.controller;

import com.receipttracker.model.User;
import com.receipttracker.repository.UserRepository;
import com.receipttracker.security.NewSignupFlag;
import com.receipttracker.service.ReferralService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReferralControllerTest {

    @Mock private ReferralService referralService;
    @Mock private UserRepository userRepo;
    @Mock private Authentication authentication;
    @Mock private OAuth2User principal;
    @Mock private HttpServletRequest request;
    @Mock private HttpSession session;

    @InjectMocks private ReferralController controller;

    @BeforeEach
    void setUp() {
        when(authentication.getPrincipal()).thenReturn(principal);
        when(principal.getAttribute("sub")).thenReturn("google-id-1");
        User user = new User();
        user.setId(1L);
        when(userRepo.findByGoogleId("google-id-1")).thenReturn(Optional.of(user));
    }

    @Test
    void claimSucceedsWhenNewSignupFlagPresent() {
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute(NewSignupFlag.SESSION_KEY)).thenReturn(true);

        ResponseEntity<?> response = controller.claim(Map.of("code", "ABC12345"), authentication, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(referralService).claim("ABC12345", 1L);
        verify(session).removeAttribute(NewSignupFlag.SESSION_KEY);
    }

    @Test
    void claimRejectedWithoutNewSignupFlag() {
        when(request.getSession(false)).thenReturn(null);

        ResponseEntity<?> response = controller.claim(Map.of("code", "ABC12345"), authentication, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(((Map<?, ?>) response.getBody()).get("error"))
                .isEqualTo("Referral codes can only be claimed right after your first login");
        verify(referralService, never()).claim(anyString(), anyLong());
    }

    @Test
    void claimRejectedWhenFlagFalse() {
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute(NewSignupFlag.SESSION_KEY)).thenReturn(false);

        ResponseEntity<?> response = controller.claim(Map.of("code", "ABC12345"), authentication, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(referralService, never()).claim(anyString(), anyLong());
    }

    @Test
    void claimPropagatesServiceErrorAsBadRequest() {
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute(NewSignupFlag.SESSION_KEY)).thenReturn(true);
        doThrow(new RuntimeException("Invalid referral code")).when(referralService).claim("BAD0000", 1L);

        ResponseEntity<?> response = controller.claim(Map.of("code", "BAD0000"), authentication, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(((Map<?, ?>) response.getBody()).get("error")).isEqualTo("Invalid referral code");
        verify(session, never()).removeAttribute(anyString());
    }

    @Test
    void mineReturnsSummary() {
        when(referralService.getMine(1L)).thenReturn(null);

        ResponseEntity<?> response = controller.mine(authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(referralService).getMine(1L);
    }
}
