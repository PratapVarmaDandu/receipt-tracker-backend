package com.receipttracker.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.receipttracker.dto.SubscriptionDTO;
import com.receipttracker.model.AppFeature;
import com.receipttracker.model.Organization;
import com.receipttracker.model.PlatformSquareConfig;
import com.receipttracker.model.Subscription;
import com.receipttracker.model.SubscriptionStatus;
import com.receipttracker.model.User;
import com.receipttracker.repository.PlatformSquareConfigRepository;
import com.receipttracker.repository.SubscriptionRepository;
import com.receipttracker.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    /**
     * Square retries a declined subscription charge on day 3, 6, and 9 after the decline
     * (its automatic dunning schedule), then gives up. The grace window matches the last
     * retry so a user is never locked out while Square is still trying to collect payment.
     */
    static final int GRACE_PERIOD_DAYS = 9;

    @Autowired private PlatformSquareConfigRepository configRepo;
    @Autowired private EncryptionService encryptionService;
    @Autowired private UserFeatureService userFeatureService;
    @Autowired private UserRepository userRepo;
    @Autowired private SubscriptionRepository subscriptionRepo;

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
        SquareSubscriptionResult sub = createSquareSubscription(baseUrl, headers, customerId, cardId, planId, cfg.getLocationId());

        // 4. Grant features immediately
        List<AppFeature> features = planIdToFeatures(cfg, planId);
        if (features.isEmpty()) throw new RuntimeException("Unknown plan ID: " + planId);
        for (AppFeature f : features) {
            userFeatureService.grantFeature(user.getId(), f);
        }

        // 5. Persist local subscription state — the source of truth for webhook resolution
        saveSubscriptionRecord(user, customerId, sub, planId);

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

            switch (eventType) {
                case "payment.completed" -> handlePaymentCompleted(cfg, event);
                case "subscription.created", "subscription.updated" -> handleSubscriptionStatusEvent(cfg, event, eventType);
                case "subscription.canceled", "subscription.deactivated" -> handleSubscriptionCanceled(cfg, event, eventType);
                case "invoice.payment_made" -> handleInvoicePaymentMade(cfg, event);
                case "invoice.payment_failed", "invoice.scheduled_charge_failed" -> handleInvoicePaymentFailed(event, eventType);
                default -> log.debug("Webhook: ignored event type {}", eventType);
            }
        } catch (Exception e) {
            log.warn("!!! Webhook processing error: {}", e.getMessage());
        }
    }

    /**
     * Legacy path, unchanged from Session 1: payment.completed carries a real buyer email
     * (unlike subscription.* events), so it still resolves via UserRepository + the
     * plan_variation_id on the payload directly.
     */
    @SuppressWarnings("unchecked")
    private void handlePaymentCompleted(PlatformSquareConfig cfg, Map<?, ?> event) {
        Map<?, ?> data = (Map<?, ?>) event.get("data");
        if (data == null) return;
        Map<?, ?> object = (Map<?, ?>) data.get("object");
        if (object == null) return;
        Map<?, ?> payment = (Map<?, ?>) object.get("payment");
        if (payment == null) return;
        String email = (String) payment.get("buyer_email_address");
        if (email == null) return;

        Optional<User> userOpt = userRepo.findByEmail(email);
        if (userOpt.isEmpty()) {
            log.warn("Webhook payment.completed: no user found for email {}", email);
            return;
        }
        String planId = extractPlanId(event);
        List<AppFeature> features = planIdToFeatures(cfg, planId);
        if (features.isEmpty()) return;

        Long userId = userOpt.get().getId();
        for (AppFeature f : features) userFeatureService.grantFeature(userId, f);
        log.info("Webhook payment.completed: granted {} to user {}", features, userId);
    }

    /**
     * Handles subscription.created/updated: reads the real Square status field and branches
     * accordingly instead of treating every event as an implicit grant. ACTIVE also extends
     * currentPeriodEnd from charged_through_date — this is the authoritative source for that
     * field (invoice.payment_made fires first but doesn't reliably carry it).
     */
    @SuppressWarnings("unchecked")
    private void handleSubscriptionStatusEvent(PlatformSquareConfig cfg, Map<?, ?> event, String eventType) {
        Map<?, ?> subPayload = extractSubscriptionPayload(event);
        if (subPayload == null) return;

        String squareSubscriptionId = (String) subPayload.get("id");
        String squareCustomerId = (String) subPayload.get("customer_id");
        String squareStatus = (String) subPayload.get("status"); // ACTIVE | PAUSED | CANCELED | DEACTIVATED | PENDING
        String chargedThroughDate = (String) subPayload.get("charged_through_date");
        String planVariationId = (String) subPayload.get("plan_variation_id");

        Subscription local = findLocalSubscription(squareSubscriptionId, squareCustomerId, planVariationId);
        if (local == null) {
            log.warn("Webhook {}: no local Subscription found for {} — dropping event", eventType, squareSubscriptionId);
            return;
        }
        if (squareStatus == null) {
            log.debug("Webhook {}: subscription {} has no status field — ignoring", eventType, squareSubscriptionId);
            return;
        }

        List<AppFeature> features = planIdToFeatures(cfg, local.getPlanId());
        if (features.isEmpty()) return;

        switch (squareStatus) {
            case "ACTIVE" -> {
                local.setStatus(SubscriptionStatus.ACTIVE);
                local.setGracePeriodEndsAt(null);
                local.setLastPaymentStatus(squareStatus);
                if (chargedThroughDate != null) {
                    local.setCurrentPeriodEnd(LocalDate.parse(chargedThroughDate).atStartOfDay());
                }
                subscriptionRepo.save(local);
                for (AppFeature f : features) userFeatureService.grantFeature(local.getUserId(), f);
                log.info("Webhook {}: subscription {} ACTIVE — granted {} to user {}",
                        eventType, squareSubscriptionId, features, local.getUserId());
            }
            case "CANCELED", "DEACTIVATED" -> {
                local.setStatus(SubscriptionStatus.CANCELED);
                local.setGracePeriodEndsAt(null);
                local.setLastPaymentStatus(squareStatus);
                subscriptionRepo.save(local);
                for (AppFeature f : features) userFeatureService.revokeFeature(local.getUserId(), f);
                log.info("Webhook {}: subscription {} {} — revoked {} from user {}",
                        eventType, squareSubscriptionId, squareStatus, features, local.getUserId());
            }
            case "PAUSED" -> {
                // Pausing is a merchant/customer choice distinct from cancellation — leave the
                // existing feature grant untouched, just record the status.
                local.setStatus(SubscriptionStatus.PAUSED);
                local.setLastPaymentStatus(squareStatus);
                subscriptionRepo.save(local);
                log.info("Webhook {}: subscription {} PAUSED", eventType, squareSubscriptionId);
            }
            default -> log.debug("Webhook {}: unhandled subscription status {} for {}",
                    eventType, squareStatus, squareSubscriptionId);
        }
    }

    /**
     * Handles the dedicated subscription.canceled/deactivated event types. Unlike
     * handleSubscriptionStatusEvent, these mean "canceled" unconditionally, regardless of
     * whether the payload happens to carry a status field.
     */
    @SuppressWarnings("unchecked")
    private void handleSubscriptionCanceled(PlatformSquareConfig cfg, Map<?, ?> event, String eventType) {
        Map<?, ?> subPayload = extractSubscriptionPayload(event);
        if (subPayload == null) return;

        String squareSubscriptionId = (String) subPayload.get("id");
        String squareCustomerId = (String) subPayload.get("customer_id");
        String planVariationId = (String) subPayload.get("plan_variation_id");

        Subscription local = findLocalSubscription(squareSubscriptionId, squareCustomerId, planVariationId);
        if (local == null) {
            log.warn("Webhook {}: no local Subscription found for {} — dropping event", eventType, squareSubscriptionId);
            return;
        }

        List<AppFeature> features = planIdToFeatures(cfg, local.getPlanId());
        if (features.isEmpty()) return;

        local.setStatus(SubscriptionStatus.CANCELED);
        local.setGracePeriodEndsAt(null);
        subscriptionRepo.save(local);

        for (AppFeature f : features) userFeatureService.revokeFeature(local.getUserId(), f);
        log.info("Webhook {}: revoked {} from user {}", eventType, features, local.getUserId());
    }

    /**
     * Handles invoice.payment_made for subscription-billed invoices (which always carry
     * subscription_id per Square's Subscriptions API docs). Clears any PAST_DUE grace window;
     * does not touch currentPeriodEnd since this payload doesn't reliably carry it — the
     * subscription.updated event that follows a successful charge is the authoritative source.
     */
    @SuppressWarnings("unchecked")
    private void handleInvoicePaymentMade(PlatformSquareConfig cfg, Map<?, ?> event) {
        Map<?, ?> invoice = extractInvoicePayload(event);
        if (invoice == null) return;

        String squareSubscriptionId = (String) invoice.get("subscription_id");
        if (squareSubscriptionId == null) return; // not a subscription invoice

        Subscription local = subscriptionRepo.findBySquareSubscriptionId(squareSubscriptionId).orElse(null);
        if (local == null) {
            log.warn("Webhook invoice.payment_made: no local Subscription found for {} — dropping event", squareSubscriptionId);
            return;
        }

        List<AppFeature> features = planIdToFeatures(cfg, local.getPlanId());
        if (features.isEmpty()) return;

        local.setStatus(SubscriptionStatus.ACTIVE);
        local.setGracePeriodEndsAt(null);
        local.setLastPaymentStatus("PAID");
        subscriptionRepo.save(local);

        for (AppFeature f : features) userFeatureService.grantFeature(local.getUserId(), f);
        log.info("Webhook invoice.payment_made: subscription {} paid — granted {} to user {}",
                squareSubscriptionId, features, local.getUserId());
    }

    /**
     * Handles invoice.payment_failed / invoice.scheduled_charge_failed (the latter is the
     * event name actually documented by Square; the former is handled defensively in case
     * Square ever sends it under that name too). Moves the subscription to PAST_DUE with a
     * grace deadline — access is intentionally NOT revoked here; the daily sweep
     * (runGracePeriodSweep) locks out only once the grace window actually expires.
     */
    @SuppressWarnings("unchecked")
    private void handleInvoicePaymentFailed(Map<?, ?> event, String eventType) {
        Map<?, ?> invoice = extractInvoicePayload(event);
        if (invoice == null) return;

        String squareSubscriptionId = (String) invoice.get("subscription_id");
        if (squareSubscriptionId == null) return;

        Subscription local = subscriptionRepo.findBySquareSubscriptionId(squareSubscriptionId).orElse(null);
        if (local == null) {
            log.warn("Webhook {}: no local Subscription found for {} — dropping event", eventType, squareSubscriptionId);
            return;
        }
        if (local.getStatus() == SubscriptionStatus.CANCELED) return; // already canceled — nothing to do

        local.setStatus(SubscriptionStatus.PAST_DUE);
        local.setGracePeriodEndsAt(LocalDateTime.now().plusDays(GRACE_PERIOD_DAYS));
        local.setLastPaymentStatus("FAILED");
        subscriptionRepo.save(local);
        log.info("Webhook {}: subscription {} PAST_DUE, grace period ends {}",
                eventType, squareSubscriptionId, local.getGracePeriodEndsAt());
    }

    /**
     * Flips any PAST_DUE subscription whose grace window has expired to CANCELED and revokes
     * the linked feature grant. Each row is processed independently so one bad row can't abort
     * the whole sweep (mirrors FormVersionScheduler's per-item isolation).
     */
    @Transactional
    public void runGracePeriodSweep() {
        PlatformSquareConfig cfg = configRepo.findFirstBy().orElse(null);
        if (cfg == null) return; // Square not configured — nothing to sweep

        List<Subscription> expired = subscriptionRepo.findByStatusAndGracePeriodEndsAtBefore(
                SubscriptionStatus.PAST_DUE, LocalDateTime.now());

        for (Subscription sub : expired) {
            try {
                sub.setStatus(SubscriptionStatus.CANCELED);
                sub.setGracePeriodEndsAt(null);
                subscriptionRepo.save(sub);

                List<AppFeature> features = planIdToFeatures(cfg, sub.getPlanId());
                for (AppFeature f : features) userFeatureService.revokeFeature(sub.getUserId(), f);

                log.info("SubscriptionService: grace period expired for subscription {} — revoked {} from user {}",
                        sub.getSquareSubscriptionId(), features, sub.getUserId());
            } catch (Exception e) {
                log.warn("!!! SubscriptionService: failed to lock out subscription {}: {}",
                        sub.getSquareSubscriptionId(), e.getMessage());
            }
        }
    }

    /** Returns the current user's local subscription rows for the "payment past due" banner / self-service. */
    @Transactional(readOnly = true)
    public List<SubscriptionDTO> getMySubscriptions() {
        User user = currentUser();
        return subscriptionRepo.findByUserId(user.getId()).stream()
                .map(this::toDTO)
                .toList();
    }

    /**
     * User-initiated cancellation. Calls Square's cancel API, which schedules cancellation for
     * the end of the current billing period rather than canceling immediately — status and
     * feature access are left untouched here; the existing subscription.canceled webhook (fired
     * by Square once the period actually ends) flips status to CANCELED and revokes access, same
     * as any other cancellation path. We only record the returned canceled_date so the frontend
     * can show "access ends on X" and hide the cancel button for an already-canceled subscription.
     */
    @Transactional
    public SubscriptionDTO cancelSubscription(Long subscriptionId) {
        User user = currentUser();
        Subscription sub = subscriptionRepo.findById(subscriptionId)
                .orElseThrow(() -> new RuntimeException("Subscription not found: " + subscriptionId));
        if (!sub.getUserId().equals(user.getId())) throw new RuntimeException("Access denied");
        if (sub.getStatus() == SubscriptionStatus.CANCELED)
            throw new RuntimeException("Subscription is already canceled");

        PlatformSquareConfig cfg = loadConfig();
        String accessToken = encryptionService.decrypt(cfg.getAccessTokenEnc());
        HttpHeaders headers = authHeaders(accessToken);
        String baseUrl = baseUrl(cfg.getEnvironment());

        SquareCancelResult result = cancelSquareSubscription(baseUrl, headers, sub.getSquareSubscriptionId());
        if (result.canceledDate() != null) {
            sub.setCancelEffectiveDate(LocalDate.parse(result.canceledDate()));
        }
        subscriptionRepo.save(sub);

        log.info("SubscriptionService: user {} scheduled cancellation of subscription {}, effective {}",
                user.getEmail(), sub.getSquareSubscriptionId(), sub.getCancelEffectiveDate());
        return toDTO(sub);
    }

    /**
     * Periodically re-fetches each ACTIVE/PAST_DUE subscription directly from Square to correct
     * drift from a webhook that was ever missed (network blip, retry exhaustion). Each row is
     * processed independently so one bad row can't abort the sweep (same isolation pattern as
     * runGracePeriodSweep / FormVersionScheduler).
     *
     * Deliberately conservative about what "drift" means: Square's subscription-level status
     * stays ACTIVE through the entire dunning retry window — it does not reflect invoice payment
     * state — so an ACTIVE row here does NOT mean a PAST_DUE local subscription should be
     * cleared. Only CANCELED/DEACTIVATED/PAUSED are treated as authoritative drift signals (a
     * genuinely missed cancellation is exactly the kind of gap this job exists to catch); ACTIVE
     * only opportunistically extends currentPeriodEnd when we're already ACTIVE locally.
     */
    @Transactional
    public void reconcileSubscriptions() {
        PlatformSquareConfig cfg = configRepo.findFirstBy().orElse(null);
        if (cfg == null) return; // Square not configured — nothing to reconcile

        String accessToken = encryptionService.decrypt(cfg.getAccessTokenEnc());
        HttpHeaders headers = authHeaders(accessToken);
        String baseUrl = baseUrl(cfg.getEnvironment());

        List<Subscription> toCheck = subscriptionRepo.findByStatusIn(
                List.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE));

        for (Subscription sub : toCheck) {
            try {
                SquareSubscriptionResult remote = fetchSquareSubscription(baseUrl, headers, sub.getSquareSubscriptionId());
                applyReconciledStatus(cfg, sub, remote);
            } catch (Exception e) {
                log.warn("!!! SubscriptionService: reconciliation failed for subscription {}: {}",
                        sub.getSquareSubscriptionId(), e.getMessage());
            }
        }
    }

    /** Pure branching logic for reconciliation, separated from the Square HTTP call so it can be unit tested directly. */
    void applyReconciledStatus(PlatformSquareConfig cfg, Subscription sub, SquareSubscriptionResult remote) {
        if (remote.status() == null) return;
        List<AppFeature> features = planIdToFeatures(cfg, sub.getPlanId());

        switch (remote.status()) {
            case "CANCELED", "DEACTIVATED" -> {
                if (sub.getStatus() == SubscriptionStatus.CANCELED) return;
                SubscriptionStatus previous = sub.getStatus();
                sub.setStatus(SubscriptionStatus.CANCELED);
                sub.setGracePeriodEndsAt(null);
                sub.setLastPaymentStatus(remote.status());
                subscriptionRepo.save(sub);
                for (AppFeature f : features) userFeatureService.revokeFeature(sub.getUserId(), f);
                log.info("SubscriptionService: reconciliation found subscription {} {} at Square but {} locally — corrected and revoked {} from user {}",
                        sub.getSquareSubscriptionId(), remote.status(), previous, features, sub.getUserId());
            }
            case "PAUSED" -> {
                if (sub.getStatus() == SubscriptionStatus.PAUSED) return;
                sub.setStatus(SubscriptionStatus.PAUSED);
                sub.setLastPaymentStatus(remote.status());
                subscriptionRepo.save(sub);
                log.info("SubscriptionService: reconciliation corrected subscription {} to PAUSED", sub.getSquareSubscriptionId());
            }
            case "ACTIVE" -> {
                if (sub.getStatus() != SubscriptionStatus.ACTIVE || remote.chargedThroughDate() == null) return;
                LocalDateTime newPeriodEnd = LocalDate.parse(remote.chargedThroughDate()).atStartOfDay();
                if (!newPeriodEnd.equals(sub.getCurrentPeriodEnd())) {
                    sub.setCurrentPeriodEnd(newPeriodEnd);
                    subscriptionRepo.save(sub);
                    log.info("SubscriptionService: reconciliation extended currentPeriodEnd for subscription {} to {}",
                            sub.getSquareSubscriptionId(), newPeriodEnd);
                }
            }
            default -> log.debug("SubscriptionService: reconciliation — unhandled remote status {} for {}",
                    remote.status(), sub.getSquareSubscriptionId());
        }
    }

    private SubscriptionDTO toDTO(Subscription s) {
        return new SubscriptionDTO(s.getId(), s.getPlanId(), s.getStatus().name(),
                s.getCurrentPeriodEnd(), s.getGracePeriodEndsAt(), s.getCancelEffectiveDate());
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

    private SquareSubscriptionResult createSquareSubscription(String baseUrl, HttpHeaders headers,
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

        Map<?, ?> sub = (Map<?, ?>) data.get("subscription");
        return new SquareSubscriptionResult(
                (String) sub.get("id"),
                (String) sub.get("status"),
                (String) sub.get("charged_through_date"));
    }

    /** Result of a Square subscription-create/get call — the fields we persist locally. */
    record SquareSubscriptionResult(String id, String status, String chargedThroughDate) {}

    /** Result of a Square subscription-cancel call. */
    private record SquareCancelResult(String status, String canceledDate) {}

    /** GET /subscriptions/{id} — used by the reconciliation sweep to detect drift from missed webhooks. */
    private SquareSubscriptionResult fetchSquareSubscription(String baseUrl, HttpHeaders headers, String squareSubscriptionId) {
        HttpEntity<Void> req = new HttpEntity<>(headers);
        ResponseEntity<Map> resp = restTemplate.exchange(
                baseUrl + "/subscriptions/" + squareSubscriptionId, HttpMethod.GET, req, Map.class);

        Map<?, ?> data = resp.getBody();
        if (data == null || !data.containsKey("subscription"))
            return new SquareSubscriptionResult(null, null, null);

        Map<?, ?> sub = (Map<?, ?>) data.get("subscription");
        return new SquareSubscriptionResult(
                (String) sub.get("id"),
                (String) sub.get("status"),
                (String) sub.get("charged_through_date"));
    }

    /** POST /subscriptions/{id}/cancel — schedules cancellation for the end of the current billing period. */
    private SquareCancelResult cancelSquareSubscription(String baseUrl, HttpHeaders headers, String squareSubscriptionId) {
        HttpEntity<Void> req = new HttpEntity<>(headers);
        ResponseEntity<Map> resp = restTemplate.exchange(
                baseUrl + "/subscriptions/" + squareSubscriptionId + "/cancel", HttpMethod.POST, req, Map.class);

        Map<?, ?> data = resp.getBody();
        if (data == null || !data.containsKey("subscription"))
            throw new RuntimeException("Failed to cancel Square subscription");

        Map<?, ?> sub = (Map<?, ?>) data.get("subscription");
        return new SquareCancelResult((String) sub.get("status"), (String) sub.get("canceled_date"));
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

    /** Looks up {@code data.object.subscription} from a subscription.* webhook event. */
    @SuppressWarnings("unchecked")
    private Map<?, ?> extractSubscriptionPayload(Map<?, ?> event) {
        Map<?, ?> data = (Map<?, ?>) event.get("data");
        if (data == null) return null;
        Map<?, ?> object = (Map<?, ?>) data.get("object");
        if (object == null) return null;
        return (Map<?, ?>) object.get("subscription");
    }

    /** Looks up {@code data.object.invoice} from an invoice.* webhook event. */
    @SuppressWarnings("unchecked")
    private Map<?, ?> extractInvoicePayload(Map<?, ?> event) {
        Map<?, ?> data = (Map<?, ?>) event.get("data");
        if (data == null) return null;
        Map<?, ?> object = (Map<?, ?>) data.get("object");
        if (object == null) return null;
        return (Map<?, ?>) object.get("invoice");
    }

    /**
     * Resolves the local {@link Subscription} row for a webhook event: by Square's own
     * subscription ID first (precise), falling back to customer ID (covers events that
     * race ahead of the row being saved, or omit the subscription ID for some reason).
     *
     * A single Square customer can legitimately own more than one local Subscription row
     * (e.g. they bought Garage and Vault as separate plans instead of the Suite) — the
     * customer-ID fallback disambiguates using the plan ID carried on the same webhook
     * payload. If that still doesn't narrow it to exactly one row, we do NOT guess: acting
     * on the wrong row would grant/revoke the wrong feature, which is worse than dropping a
     * rare unresolvable event (reconciliation catches genuine drift on its next daily pass).
     */
    private Subscription findLocalSubscription(String squareSubscriptionId, String squareCustomerId, String planId) {
        if (squareSubscriptionId != null) {
            Optional<Subscription> byId = subscriptionRepo.findBySquareSubscriptionId(squareSubscriptionId);
            if (byId.isPresent()) return byId.get();
        }
        if (squareCustomerId == null) return null;

        List<Subscription> byCustomer = subscriptionRepo.findBySquareCustomerId(squareCustomerId);
        if (byCustomer.isEmpty()) return null;
        if (byCustomer.size() == 1) return byCustomer.get(0);

        if (planId != null) {
            List<Subscription> byCustomerAndPlan = subscriptionRepo.findBySquareCustomerIdAndPlanId(squareCustomerId, planId);
            if (byCustomerAndPlan.size() == 1) return byCustomerAndPlan.get(0);
        }

        log.warn("Webhook: ambiguous customer {} has {} local Subscription rows and plan {} did not narrow " +
                        "to a single match — candidates {} — dropping event instead of guessing",
                squareCustomerId, byCustomer.size(), planId,
                byCustomer.stream().map(Subscription::getSquareSubscriptionId).toList());
        return null;
    }

    /** Persists (or updates) the local {@link Subscription} row created at purchase time. */
    private void saveSubscriptionRecord(User user, String squareCustomerId, SquareSubscriptionResult sub, String planId) {
        Subscription record = subscriptionRepo.findBySquareSubscriptionId(sub.id())
                .orElseGet(Subscription::new);
        record.setSquareSubscriptionId(sub.id());
        record.setSquareCustomerId(squareCustomerId);
        record.setUserId(user.getId());
        record.setPlanId(planId);
        record.setStatus(SubscriptionStatus.ACTIVE);
        record.setLastPaymentStatus(sub.status());
        record.setGracePeriodEndsAt(null);
        if (sub.chargedThroughDate() != null) {
            record.setCurrentPeriodEnd(LocalDate.parse(sub.chargedThroughDate()).atStartOfDay());
        }
        subscriptionRepo.save(record);
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
