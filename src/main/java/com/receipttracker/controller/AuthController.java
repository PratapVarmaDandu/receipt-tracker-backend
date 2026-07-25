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
            String sub = oAuth2User.getAttribute("sub");
            String email = oAuth2User.getAttribute("email");
            log.debug("GET /api/auth/me - Checking user: sub={}, email={}", sub, email);

            HttpSession session = request.getSession(false);
            boolean isNewSignup = session != null && Boolean.TRUE.equals(session.getAttribute(NewSignupFlag.SESSION_KEY));

            java.util.Optional<User> userOpt = userRepository.findByGoogleId(sub);
            if (userOpt.isEmpty()) {
                userOpt = userRepository.findByAppleId(sub);
            }
            if (userOpt.isEmpty() && email != null) {
                userOpt = userRepository.findByEmail(email);
            }

            ResponseEntity<?> result = userOpt
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
            
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("!!! GET /api/auth/me FAILED - duration={}ms, error={}", duration, e.getMessage(), e);
            throw e;
        }
    }

    @PostMapping("/apple")
    public ResponseEntity<?> authenticateApple(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String appleUserId = body.get("appleUserId");
        String email = body.get("email");
        String fullName = body.get("fullName");

        if (appleUserId == null || appleUserId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "appleUserId is required"));
        }

        log.info("POST /api/auth/apple - appleUserId={}, email={}, name={}", appleUserId, email, fullName);

        User user = userRepository.findByAppleId(appleUserId)
                .orElseGet(() -> {
                    String targetEmail = (email != null && !email.isBlank()) ? email : (appleUserId + "@privaterelay.appleid.com");
                    return userRepository.findByEmail(targetEmail)
                            .map(existing -> {
                                existing.setAppleId(appleUserId);
                                if (fullName != null && !fullName.isBlank()) existing.setName(fullName);
                                return userRepository.save(existing);
                            })
                            .orElseGet(() -> {
                                User newUser = new User();
                                newUser.setAppleId(appleUserId);
                                newUser.setEmail(targetEmail);
                                newUser.setName((fullName != null && !fullName.isBlank()) ? fullName : "Apple User");
                                return userRepository.save(newUser);
                            });
                });

        return authenticateUserSession(user, "apple", request);
    }

    @PostMapping("/google")
    public ResponseEntity<?> authenticateGoogle(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String googleId = body.get("googleId");
        String email = body.get("email");
        String name = body.get("name");
        String picture = body.get("picture");

        if (googleId == null || googleId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "googleId is required"));
        }

        log.info("POST /api/auth/google - googleId={}, email={}, name={}", googleId, email, name);

        User user = userRepository.findByGoogleId(googleId)
                .orElseGet(() -> {
                    String targetEmail = (email != null && !email.isBlank()) ? email : (googleId + "@gmail.com");
                    return userRepository.findByEmail(targetEmail)
                            .map(existing -> {
                                existing.setGoogleId(googleId);
                                if (name != null && !name.isBlank()) existing.setName(name);
                                if (picture != null && !picture.isBlank()) existing.setPicture(picture);
                                return userRepository.save(existing);
                            })
                            .orElseGet(() -> {
                                User newUser = new User();
                                newUser.setGoogleId(googleId);
                                newUser.setEmail(targetEmail);
                                newUser.setName((name != null && !name.isBlank()) ? name : "Google User");
                                newUser.setPicture(picture);
                                return userRepository.save(newUser);
                            });
                });

        return authenticateUserSession(user, "google", request);
    }

    private ResponseEntity<?> authenticateUserSession(User user, String provider, HttpServletRequest request) {
        String subKey = user.getGoogleId() != null ? user.getGoogleId() : (user.getAppleId() != null ? user.getAppleId() : user.getEmail());
        Map<String, Object> attrs = Map.of(
                "sub",   subKey,
                "email", user.getEmail(),
                "name",  user.getName() != null ? user.getName() : ""
        );
        OAuth2User principal = new DefaultOAuth2User(
                Collections.singleton(new SimpleGrantedAuthority("ROLE_USER")),
                attrs, "sub");
        OAuth2AuthenticationToken auth = new OAuth2AuthenticationToken(
                principal, principal.getAuthorities(), provider);

        SecurityContextHolder.getContext().setAuthentication(auth);
        
        HttpSession session = request.getSession(true);
        session.setAttribute("SPRING_SECURITY_CONTEXT", SecurityContextHolder.getContext());
        String sessionId = session.getId();

        String refreshToken = refreshTokenService.createToken(user);

        log.info("Successfully authenticated user id={} ({}) via provider={}", user.getId(), user.getEmail(), provider);

        return ResponseEntity.ok(Map.of(
                "sessionId",    sessionId,
                "refreshToken", refreshToken,
                "user", Map.of(
                        "id",    user.getId(),
                        "name",  user.getName() != null ? user.getName() : "",
                        "email", user.getEmail() != null ? user.getEmail() : ""
                )
        ));
    }

    @PostMapping("/dismiss-welcome")
    public ResponseEntity<?> dismissWelcome(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return ResponseEntity.status(401).build();
        }
        String sub = ((OAuth2User) authentication.getPrincipal()).getAttribute("sub");
        java.util.Optional<User> userOpt = userRepository.findByGoogleId(sub);
        if (userOpt.isEmpty()) {
            userOpt = userRepository.findByAppleId(sub);
        }
        userOpt.ifPresent(user -> {
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
            String subKey = user.getGoogleId() != null ? user.getGoogleId() : (user.getAppleId() != null ? user.getAppleId() : user.getEmail());
            Map<String, Object> attrs = Map.of(
                    "sub",   subKey,
                    "email", user.getEmail(),
                    "name",  user.getName() != null ? user.getName() : ""
            );
            OAuth2User principal = new DefaultOAuth2User(
                    Collections.singleton(new SimpleGrantedAuthority("ROLE_USER")),
                    attrs, "sub");
            OAuth2AuthenticationToken auth = new OAuth2AuthenticationToken(
                    principal, principal.getAuthorities(), "oauth");

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
