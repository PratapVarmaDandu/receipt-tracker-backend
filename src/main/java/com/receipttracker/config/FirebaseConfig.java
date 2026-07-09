package com.receipttracker.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.FileInputStream;
import java.io.IOException;

/**
 * Only registers a FirebaseMessaging bean when push.enabled=true and a service-account
 * JSON path is configured. Otherwise PushNotificationService's @Autowired(required=false)
 * field stays null and it logs+no-ops — same non-fatal pattern as EmailService without SMTP.
 */
@Configuration
public class FirebaseConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    @Value("${push.fcm.service-account-json:}")
    private String serviceAccountPath;

    @Bean
    @ConditionalOnProperty(prefix = "push", name = "enabled", havingValue = "true")
    public FirebaseMessaging firebaseMessaging() {
        if (serviceAccountPath == null || serviceAccountPath.isBlank()) {
            log.warn("!!! push.enabled=true but push.fcm.service-account-json is not set — push disabled");
            return null;
        }
        try (FileInputStream serviceAccount = new FileInputStream(serviceAccountPath)) {
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                    .build();
            FirebaseApp app = FirebaseApp.getApps().isEmpty()
                    ? FirebaseApp.initializeApp(options)
                    : FirebaseApp.getInstance();
            return FirebaseMessaging.getInstance(app);
        } catch (IOException e) {
            log.warn("!!! Failed to initialize Firebase — push disabled: {}", e.getMessage());
            return null;
        }
    }
}
