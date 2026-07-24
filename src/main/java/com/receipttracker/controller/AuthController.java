package com.receipttracker.controller;

import com.receipttracker.model.User;
import com.receipttracker.repository.UserRepository;
import com.receipttracker.security.NewSignupFlag;
import com.receipttracker.service.RefreshTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @GetMapping("/me")
    public ResponseEntity<?> me(Authentication authentication, HttpServletRequest request) {
        log.trace(">>> GET /api/auth/me");
        long startTime = System.currentTimeMillis();

        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            long duration = System.currentTimeMillis() - startTime;
            log.info("<<< GET /api/auth/me - NOT_AUTHENTICATED, duration={}ms", duration);
            return ResponseEntity.ok(Map.of("authenticated", false));
        }

        try {
            OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
            String googleId = oAuth2User.getAttribute("sub");
            log.debug("GET /api/auth/me - Checking user: googleId={}", googleId);

            HttpSession session = request.getSession(false);
            boolean isNewSignup = session != null && Boolean.TRUE.equals(session.getAttribute(NewSignupFlag.SESSION_KEY));

            ResponseEntity<?> result = userRepository.findByGoogleId(googleId)
                .map(user -> {
                    long duration = System.currentTimeMillis() - startTime;
                    log.info("<<< GET /api/auth/me - AUTHENTICATED: userId={}, email={}, duration={}ms",
                            user.getId(), user.getEmail(), duration);
                    return ResponseEntity.ok((Object) Map.of(
                        "authenticated",     true,
                        "id",                user.getId(),
                        "name",              user.getName()    != null ? user.getName()    : "",
                        "email",             user.getEmail()   != null ? user.getEmail()   : "",
                        "picture",           user.getPicture() != null ? user.getPicture() : "",
                        "welcomeDismissed",  user.isWelcomeDismissed(),
                        "storageConfigured", user.isStorageConfigured(),
                        "platformAdmin",     Boolean.TRUE.equals(user.getPlatformAdmin()),
                        "isNewUser",         isNewSignup
                    ));
                })
                .orElse(ResponseEntity.ok(Map.of("authenticated", false)));
            
            if (result.getStatusCode().isError()) {
                long duration = System.currentTimeMillis() - startTime;
                log.warn("<<< GET /api/auth/me - USER_NOT_FOUND: googleId={}, duration={}ms", googleId, duration);
            }
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("!!! GET /api/auth/me FAILED - duration={}ms, error={}", duration, e.getMessage(), e);
            throw e;
        }
    }

    @PostMapping("/dismiss-welcome")
    public ResponseEntity<?> dismissWelcome(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return ResponseEntity.status(401).build();
        }
        String googleId = ((OAuth2User) authentication.getPrincipal()).getAttribute("sub");
        userRepository.findByGoogleId(googleId).ifPresent(user -> {
            user.setWelcomeDismissed(true);
            userRepository.save(user);
            log.info("Welcome banner dismissed for userId={}", user.getId());
        });
        return ResponseEntity.ok().build();
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String rawRefreshToken = body.get("refreshToken");
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "refreshToken is required"));
        }

        try {
            // Validate and rotate the refresh token
            RefreshTokenService.RotationResult result = refreshTokenService.validateAndRotate(rawRefreshToken);
            User user = result.getUser();
            String nextRawToken = result.getRotatedRawToken();

            // Programmatically authenticate user into Spring Security context
            Map<String, Object> attrs = Map.of(
                    "sub",   user.getGoogleId(),
                    "email", user.getEmail(),
                    "name",  user.getName() != null ? user.getName() : ""
            );
            OAuth2User principal = new DefaultOAuth2User(
                    Collections.singleton(new SimpleGrantedAuthority("ROLE_USER")),
                    attrs, "sub");
            OAuth2AuthenticationToken auth = new OAuth2AuthenticationToken(
                    principal, principal.getAuthorities(), "google");

            SecurityContextHolder.getContext().setAuthentication(auth);
            
            // Re-bind session
            HttpSession session = request.getSession(true);
            session.setAttribute("SPRING_SECURITY_CONTEXT", SecurityContextHolder.getContext());
            String sessionId = session.getId();

            log.info("Programmatically authenticated user id={} via refresh token", user.getId());

            return ResponseEntity.ok(Map.of(
                    "sessionId",    sessionId,
                    "refreshToken", nextRawToken
            ));
        } catch (Exception e) {
            log.warn("Refresh token authentication failed: {}", e.getMessage());
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        }
    }
}
