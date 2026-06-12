package com.receipttracker.controller;

import com.receipttracker.service.SubscriptionService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/webhooks/square")
public class SquareWebhookController {

    private static final Logger log = LoggerFactory.getLogger(SquareWebhookController.class);

    @Autowired private SubscriptionService subscriptionService;

    @Value("${square.webhook.enabled:false}")
    private boolean webhookEnabled;

    @PostMapping("/subscriptions")
    public ResponseEntity<Void> handleSubscriptionWebhook(
            @RequestBody String body,
            @RequestHeader(value = "Square-Signature", required = false) String signature,
            HttpServletRequest request) {

        if (!webhookEnabled) {
            log.debug("Square webhook disabled — ignoring incoming event");
            return ResponseEntity.notFound().build();
        }

        String webhookUrl = buildWebhookUrl(request);
        log.info(">>> Square webhook received type={}", extractType(body));

        try {
            subscriptionService.processWebhook(body, signature, webhookUrl);
        } catch (Exception e) {
            // Always return 200 — Square retries on non-200 responses
            log.warn("!!! Webhook processing error (still returning 200): {}", e.getMessage());
        }

        return ResponseEntity.ok().build();
    }

    private String buildWebhookUrl(HttpServletRequest request) {
        String proto = request.getHeader("X-Forwarded-Proto");
        if (proto == null) proto = request.getScheme();
        String host = request.getHeader("X-Forwarded-Host");
        if (host == null) host = request.getHeader("Host");
        return proto + "://" + host + request.getRequestURI();
    }

    private String extractType(String body) {
        try {
            int idx = body.indexOf("\"type\"");
            if (idx < 0) return "unknown";
            int colon = body.indexOf(':', idx);
            int q1 = body.indexOf('"', colon + 1);
            int q2 = body.indexOf('"', q1 + 1);
            return body.substring(q1 + 1, q2);
        } catch (Exception e) {
            return "unknown";
        }
    }
}
