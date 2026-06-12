package com.receipttracker.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.receipttracker.model.AppFeature;
import com.receipttracker.model.Organization;
import com.receipttracker.model.PlatformSquareConfig;
import com.receipttracker.model.User;
import com.receipttracker.repository.PlatformSquareConfigRepository;
import com.receipttracker.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    @Autowired private PlatformSquareConfigRepository configRepo;
    @Autowired private EncryptionService encryptionService;
    @Autowired private UserFeatureService userFeatureService;
    @Autowired private UserRepository userRepo;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Purchases a subscription using Square APIs.
     * Flow: load platform config → create/find customer → create card → create subscription.
     * Feature is granted immediately on success; webhook is the backup for renewals.
     * Returns the granted feature name (or comma-separated list for suite).
     */
    public String purchaseSubscription(String sourceId, String planId) {
        PlatformSquareConfig cfg = loadConfig();
        String accessToken = encryptionService.decrypt(cfg.getAccessTokenEnc());
        String baseUrl = baseUrl(cfg.getEnvironment());

        User user = currentUser();
        HttpHeaders headers = authHeaders(accessToken);

        // 1. Create or find Square customer for this user
        String customerId = findOrCreateCustomer(baseUrl, headers, user.getEmail(), user.getName());

        // 2. Create a stored card from the payment nonce
        String cardId = createCard(baseUrl, headers, customerId, sourceId);

        // 3. Create the subscription
        createSquareSubscription(baseUrl, headers, customerId, cardId, planId, cfg.getLocationId());

        // 4. Grant features immediately
        List<AppFeature> features = planIdToFeatures(cfg, planId);
        if (features.isEmpty()) throw new RuntimeException("Unknown plan ID: " + planId);
        for (AppFeature f : features) {
            userFeatureService.grantFeature(user.getId(), f);
        }
        log.info("Subscription created for user {} plan {} features {}", user.getEmail(), planId, features);

        if (features.size() == 1) return features.get(0).name();
        return "FULL_SUITE";
    }

    /**
     * Processes an inbound Square webhook event.
     * Validates the HMAC-SHA1 signature, then grants or revokes features.
     * Always call this inside a try/catch — exceptions should not propagate to the 200 response.
     */
    public void processWebhook(String body, String signatureHeader, String webhookUrl) {
        PlatformSquareConfig cfg = loadConfig();
        String sigKey = encryptionService.decrypt(cfg.getWebhookSignatureKeyEnc());

        if (!validateSignature(webhookUrl, body, sigKey, signatureHeader)) {
            log.warn("!!! Square webhook signature mismatch — ignoring");
            return;
        }

        try {
            Map<?, ?> event = objectMapper.readValue(body, Map.class);
            String eventType = (String) event.get("type");
            if (eventType == null) return;

            String customerEmail = extractCustomerEmail(event);
            String planId = extractPlanId(event);
            if (customerEmail == null || planId == null) return;

            Optional<User> userOpt = userRepo.findByEmail(customerEmail);
            if (userOpt.isEmpty()) {
                log.warn("Webhook: no user found for email {}", customerEmail);
                return;
            }
            Long userId = userOpt.get().getId();
            List<AppFeature> features = planIdToFeatures(cfg, planId);
            if (features.isEmpty()) return;

            switch (eventType) {
                case "subscription.created", "subscription.updated", "payment.completed" -> {
                    for (AppFeature f : features) userFeatureService.grantFeature(userId, f);
                    log.info("Webhook {}: granted {} to user {}", eventType, features, customerEmail);
                }
                case "subscription.canceled", "subscription.deactivated" -> {
                    for (AppFeature f : features) userFeatureService.revokeFeature(userId, f);
                    log.info("Webhook {}: revoked {} from user {}", eventType, features, customerEmail);
                }
                default -> log.debug("Webhook: ignored event type {}", eventType);
            }
        } catch (Exception e) {
            log.warn("!!! Webhook processing error: {}", e.getMessage());
        }
    }

    // ── Square API calls ──────────────────────────────────────────────────────

    private String findOrCreateCustomer(String baseUrl, HttpHeaders headers, String email, String name) {
        // Search for existing customer by email
        try {
            Map<String, Object> searchBody = new LinkedHashMap<>();
            Map<String, Object> query = new LinkedHashMap<>();
            Map<String, Object> filter = new LinkedHashMap<>();
            Map<String, Object> emailFilter = new LinkedHashMap<>();
            emailFilter.put("exact", email);
            filter.put("email_address", emailFilter);
            query.put("filter", filter);
            searchBody.put("query", query);

            HttpEntity<Map<String, Object>> req = new HttpEntity<>(searchBody, headers);
            ResponseEntity<CustomerSearchResponse> resp = restTemplate.exchange(
                    baseUrl + "/customers/search", HttpMethod.POST, req, CustomerSearchResponse.class);

            CustomerSearchResponse data = resp.getBody();
            if (data != null && data.customers != null && !data.customers.isEmpty()) {
                return data.customers.get(0).id;
            }
        } catch (Exception e) {
            log.debug("Customer search failed (will create): {}", e.getMessage());
        }

        // Create new customer
        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("idempotency_key", UUID.randomUUID().toString());
        createBody.put("email_address", email);
        if (name != null && !name.isBlank()) {
            String[] parts = name.trim().split(" ", 2);
            createBody.put("given_name", parts[0]);
            if (parts.length > 1) createBody.put("family_name", parts[1]);
        }

        HttpEntity<Map<String, Object>> req = new HttpEntity<>(createBody, headers);
        ResponseEntity<CustomerCreateResponse> resp = restTemplate.exchange(
                baseUrl + "/customers", HttpMethod.POST, req, CustomerCreateResponse.class);

        CustomerCreateResponse data = resp.getBody();
        if (data == null || data.customer == null || data.customer.id == null)
            throw new RuntimeException("Failed to create Square customer");
        return data.customer.id;
    }

    private String createCard(String baseUrl, HttpHeaders headers, String customerId, String sourceId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("idempotency_key", UUID.randomUUID().toString());
        body.put("source_id", sourceId);
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("customer_id", customerId);
        body.put("card", card);

        HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, headers);
        ResponseEntity<CardCreateResponse> resp = restTemplate.exchange(
                baseUrl + "/cards", HttpMethod.POST, req, CardCreateResponse.class);

        CardCreateResponse data = resp.getBody();
        if (data == null || data.card == null || data.card.id == null)
            throw new RuntimeException("Failed to store card");
        return data.card.id;
    }

    private void createSquareSubscription(String baseUrl, HttpHeaders headers,
                                           String customerId, String cardId,
                                           String planVariationId, String locationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("idempotency_key", UUID.randomUUID().toString());
        body.put("location_id",          locationId);
        body.put("plan_variation_id",    planVariationId);
        body.put("customer_id",          customerId);
        body.put("card_id",              cardId);
        body.put("start_date",           LocalDate.now().toString());

        HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, headers);
        ResponseEntity<Map> resp = restTemplate.exchange(
                baseUrl + "/subscriptions", HttpMethod.POST, req, Map.class);

        Map<?, ?> data = resp.getBody();
        if (data == null || !data.containsKey("subscription"))
            throw new RuntimeException("Failed to create Square subscription");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<AppFeature> planIdToFeatures(PlatformSquareConfig cfg, String planId) {
        if (planId == null) return List.of();
        if (planId.equals(cfg.getPlanIdGarage())) return List.of(AppFeature.GARAGE);
        if (planId.equals(cfg.getPlanIdVault()))  return List.of(AppFeature.DOCUMENT_VAULT);
        if (planId.equals(cfg.getPlanIdJobs()))   return List.of(AppFeature.JOB_TRACKER);
        if (planId.equals(cfg.getPlanIdSuite()))  return List.of(
                AppFeature.GARAGE, AppFeature.DOCUMENT_VAULT,
                AppFeature.JOB_TRACKER, AppFeature.EXPENSE_SHARING);
        return List.of();
    }

    private PlatformSquareConfig loadConfig() {
        return configRepo.findFirstBy()
                .orElseThrow(() -> new RuntimeException("Platform Square is not configured"));
    }

    private HttpHeaders authHeaders(String accessToken) {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", "Bearer " + accessToken);
        h.set("Square-Version", "2024-11-20");
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private String baseUrl(Organization.SquareEnv env) {
        return env == Organization.SquareEnv.PRODUCTION
                ? "https://connect.squareup.com/v2"
                : "https://connect.squareupsandbox.com/v2";
    }

    /**
     * Validates a Square webhook signature.
     * Square signs with: HMAC-SHA1(notificationUrl + rawBody, webhookSignatureKey)
     * and Base64-encodes the result.
     */
    boolean validateSignature(String webhookUrl, String body, String sigKey, String sigHeader) {
        try {
            String payload = webhookUrl + body;
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(sigKey.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            byte[] hmac = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String computed = Base64.getEncoder().encodeToString(hmac);
            return computed.equals(sigHeader);
        } catch (Exception e) {
            log.warn("!!! Webhook signature validation error: {}", e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private String extractCustomerEmail(Map<?, ?> event) {
        try {
            Map<?, ?> data = (Map<?, ?>) event.get("data");
            if (data == null) return null;
            Map<?, ?> object = (Map<?, ?>) data.get("object");
            if (object == null) return null;
            // subscription events: object.subscription.customer_id -> need customer lookup
            // payment events: object.payment.buyer_email_address
            Map<?, ?> payment = (Map<?, ?>) object.get("payment");
            if (payment != null) return (String) payment.get("buyer_email_address");
            Map<?, ?> subscription = (Map<?, ?>) object.get("subscription");
            if (subscription != null) return (String) subscription.get("customer_email");
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private String extractPlanId(Map<?, ?> event) {
        try {
            Map<?, ?> data = (Map<?, ?>) event.get("data");
            if (data == null) return null;
            Map<?, ?> object = (Map<?, ?>) data.get("object");
            if (object == null) return null;
            Map<?, ?> subscription = (Map<?, ?>) object.get("subscription");
            if (subscription == null) return null;
            return (String) subscription.get("plan_variation_id");
        } catch (Exception e) {
            return null;
        }
    }

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        OAuth2User principal = (OAuth2User) auth.getPrincipal();
        String email = principal.getAttribute("email");
        return userRepo.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found: " + email));
    }

    // ── Square API response POJOs ─────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class CustomerSearchResponse {
        @JsonProperty("customers") List<CustomerObj> customers;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class CustomerCreateResponse {
        @JsonProperty("customer") CustomerObj customer;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class CustomerObj {
        @JsonProperty("id")            String id;
        @JsonProperty("email_address") String emailAddress;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class CardCreateResponse {
        @JsonProperty("card") CardObj card;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class CardObj {
        @JsonProperty("id") String id;
    }
}
