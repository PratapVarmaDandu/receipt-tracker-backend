package com.receipttracker.service;

import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.FirebaseMessaging;
import com.receipttracker.model.PushDeviceToken;
import com.receipttracker.model.PushPlatform;
import com.receipttracker.model.User;
import com.receipttracker.repository.PushDeviceTokenRepository;
import com.receipttracker.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class PushNotificationService {

    private static final Logger log = LoggerFactory.getLogger(PushNotificationService.class);

    // Null unless push.enabled=true and Firebase creds are configured (see FirebaseConfig).
    @Autowired(required = false)
    private FirebaseMessaging firebaseMessaging;

    @Autowired private PushDeviceTokenRepository tokenRepo;
    @Autowired private UserRepository userRepo;

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        OAuth2User principal = (OAuth2User) auth.getPrincipal();
        String googleId = principal.getAttribute("sub");
        return userRepo.findByGoogleId(googleId)
                .orElseThrow(() -> new RuntimeException("Authenticated user not found"));
    }

    @Transactional
    public void registerToken(String token, String platformStr) {
        if (token == null || token.isBlank()) {
            throw new RuntimeException("token is required");
        }
        PushPlatform platform;
        try {
            platform = PushPlatform.valueOf(platformStr);
        } catch (Exception e) {
            throw new RuntimeException("platform must be IOS or ANDROID");
        }

        User user = currentUser();
        PushDeviceToken existing = tokenRepo.findByUserAndToken(user, token).orElse(null);
        if (existing != null) {
            existing.setLastSeenAt(LocalDateTime.now());
            existing.setPlatform(platform);
            tokenRepo.save(existing);
            return;
        }

        PushDeviceToken deviceToken = new PushDeviceToken();
        deviceToken.setUser(user);
        deviceToken.setToken(token);
        deviceToken.setPlatform(platform);
        tokenRepo.save(deviceToken);
    }

    @Transactional
    public void unregisterToken(String token) {
        if (token == null || token.isBlank()) return;
        tokenRepo.deleteByToken(token);
    }

    /** Non-fatal: logs and no-ops when Firebase isn't configured, same pattern as EmailService without SMTP. */
    @Transactional(readOnly = true)
    public void sendToUser(User user, String title, String body) {
        List<PushDeviceToken> tokens = tokenRepo.findByUser(user);
        if (tokens.isEmpty()) return;
        if (firebaseMessaging == null) {
            log.warn("!!! push not configured — skipping push to userId={}", user.getId());
            return;
        }
        for (PushDeviceToken deviceToken : tokens) {
            try {
                Message message = Message.builder()
                        .setToken(deviceToken.getToken())
                        .setNotification(Notification.builder().setTitle(title).setBody(body).build())
                        .build();
                firebaseMessaging.send(message);
            } catch (FirebaseMessagingException e) {
                log.warn("!!! push send failed for tokenId={}: {}", deviceToken.getId(), e.getMessage());
            }
        }
    }
}
