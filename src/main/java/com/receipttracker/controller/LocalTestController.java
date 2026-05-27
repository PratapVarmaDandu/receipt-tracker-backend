package com.receipttracker.controller;

import com.receipttracker.service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Dev-only endpoints available only when spring.profiles.active=local.
 * Never compiled into prod/test builds.
 */
@RestController
@RequestMapping("/api/test")
@Profile("local")
public class LocalTestController {

    private static final Logger log = LoggerFactory.getLogger(LocalTestController.class);

    @Autowired
    private EmailService emailService;

    /**
     * Sends a test invite email to verify Gmail SMTP credentials.
     * Usage: POST /api/test/email?to=someone@example.com
     */
    @PostMapping("/email")
    public ResponseEntity<?> testEmail(@RequestParam String to) {
        log.info(">>> test email to={}", to);
        emailService.sendInvite(
                to,
                "Test Store",
                "Local Dev User",
                new BigDecimal("42.00"),
                "http://localhost:4200/share/test-token-123"
        );
        return ResponseEntity.ok(Map.of(
                "status", "sent (check logs if SMTP not configured)",
                "to", to
        ));
    }
}
