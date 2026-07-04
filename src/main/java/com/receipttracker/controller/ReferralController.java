package com.receipttracker.controller;

import com.receipttracker.dto.ReferralSummaryDTO;
import com.receipttracker.repository.UserRepository;
import com.receipttracker.security.NewSignupFlag;
import com.receipttracker.service.ReferralService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/referrals")
public class ReferralController {

    private static final Logger log = LoggerFactory.getLogger(ReferralController.class);

    @Autowired private ReferralService referralService;
    @Autowired private UserRepository userRepo;

    @GetMapping("/mine")
    public ResponseEntity<?> mine(Authentication authentication) {
        try {
            ReferralSummaryDTO summary = referralService.getMine(currentUserId(authentication));
            return ResponseEntity.ok(summary);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Gated on the one-shot "just signed up" session flag (see NewSignupFlag /
     * CustomOAuth2UserService / OAuth2SuccessHandler) rather than trusting the frontend
     * to only call this once — an existing account calling this endpoint directly
     * outside the window right after its own first login is rejected, which is what
     * stops an old account from retroactively "claiming" a referral for a friend.
     */
    @PostMapping("/claim")
    public ResponseEntity<?> claim(@RequestBody Map<String, String> body,
                                    Authentication authentication,
                                    HttpServletRequest request) {
        try {
            HttpSession session = request.getSession(false);
            if (session == null || !Boolean.TRUE.equals(session.getAttribute(NewSignupFlag.SESSION_KEY))) {
                throw new RuntimeException("Referral codes can only be claimed right after your first login");
            }
            referralService.claim(body.get("code"), currentUserId(authentication));
            session.removeAttribute(NewSignupFlag.SESSION_KEY);
            return ResponseEntity.ok(Map.of("claimed", true));
        } catch (Exception e) {
            log.warn("Referral claim failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private Long currentUserId(Authentication authentication) {
        OAuth2User principal = (OAuth2User) authentication.getPrincipal();
        String googleId = principal.getAttribute("sub");
        return userRepo.findByGoogleId(googleId)
                .orElseThrow(() -> new RuntimeException("Authenticated user not found"))
                .getId();
    }
}
