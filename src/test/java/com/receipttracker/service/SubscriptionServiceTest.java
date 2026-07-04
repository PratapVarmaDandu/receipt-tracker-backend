package com.receipttracker.service;

import com.receipttracker.model.AppFeature;
import com.receipttracker.model.Organization;
import com.receipttracker.model.PlatformSquareConfig;
import com.receipttracker.model.Subscription;
import com.receipttracker.model.SubscriptionStatus;
import com.receipttracker.model.User;
import com.receipttracker.repository.PlatformSquareConfigRepository;
import com.receipttracker.repository.SubscriptionRepository;
import com.receipttracker.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Session 1: subscription lifecycle webhooks (which never carry a customer email in Square's
 * payload) must resolve the local user via the persisted {@link Subscription} row keyed by
 * squareSubscriptionId/squareCustomerId, not by parsing an email that isn't there.
 *
 * Session 2: invoice.payment_made / invoice.scheduled_charge_failed lifecycle handling, the
 * subscription.updated real-status branching (no more implicit grant-on-every-event), and the
 * grace-period sweep that locks out PAST_DUE subscriptions once the grace window expires.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubscriptionServiceTest {

    private static final String WEBHOOK_URL = "https://example.com/api/webhooks/square/subscriptions";
    private static final String SIGNING_KEY = "test-signing-key";

    @Mock private PlatformSquareConfigRepository configRepo;
    @Mock private EncryptionService encryptionService;
    @Mock private UserFeatureService userFeatureService;
    @Mock private UserRepository userRepo;
    @Mock private SubscriptionRepository subscriptionRepo;
    @Mock private RestTemplate restTemplate;

    @InjectMocks private SubscriptionService service;

    private PlatformSquareConfig cfg;

    @BeforeEach
    void setUp() {
        cfg = new PlatformSquareConfig();
        cfg.setAccessTokenEnc("enc-token");
        cfg.setWebhookSignatureKeyEnc("enc-sigkey");
        cfg.setEnvironment(Organization.SquareEnv.SANDBOX);
        cfg.setPlanIdGarage("plan_garage_1");

        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);

        when(configRepo.findFirstBy()).thenReturn(Optional.of(cfg));
        when(encryptionService.decrypt("enc-sigkey")).thenReturn(SIGNING_KEY);
        when(encryptionService.decrypt("enc-token")).thenReturn("square-access-token");
    }

    private void mockSecurityContext(User user) {
        OAuth2User principal = mock(OAuth2User.class);
        when(principal.getAttribute("email")).thenReturn(user.getEmail());
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(principal);
        SecurityContext ctx = mock(SecurityContext.class);
        when(ctx.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(ctx);
        when(userRepo.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> squareResponse(Map<String, Object> subscriptionFields) {
        return new ResponseEntity<>(Map.of("subscription", subscriptionFields), HttpStatus.OK);
    }

    @Test
    void subscriptionCanceledResolvesUserViaLocalSubscriptionRowAndRevokesFeature() {
        Subscription localSub = new Subscription();
        localSub.setSquareSubscriptionId("sub_123");
        localSub.setSquareCustomerId("cust_456");
        localSub.setUserId(42L);
        localSub.setPlanId("plan_garage_1");
        localSub.setStatus(SubscriptionStatus.ACTIVE);
        when(subscriptionRepo.findBySquareSubscriptionId("sub_123")).thenReturn(Optional.of(localSub));

        String body = subscriptionEventBody("subscription.canceled", "sub_123", "cust_456", "plan_garage_1");

        service.processWebhook(body, sign(body), WEBHOOK_URL);

        verify(userFeatureService).revokeFeature(42L, AppFeature.GARAGE);
        verify(userRepo, never()).findByEmail(any());

        ArgumentCaptor<Subscription> saved = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepo).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(SubscriptionStatus.CANCELED);
    }

    @Test
    void subscriptionCanceledFallsBackToCustomerIdWhenSubscriptionIdUnknownLocally() {
        Subscription localSub = new Subscription();
        localSub.setSquareSubscriptionId("sub_other");
        localSub.setSquareCustomerId("cust_456");
        localSub.setUserId(99L);
        localSub.setPlanId("plan_garage_1");
        localSub.setStatus(SubscriptionStatus.ACTIVE);
        when(subscriptionRepo.findBySquareSubscriptionId("sub_123")).thenReturn(Optional.empty());
        when(subscriptionRepo.findBySquareCustomerId("cust_456")).thenReturn(java.util.List.of(localSub));

        String body = subscriptionEventBody("subscription.canceled", "sub_123", "cust_456", "plan_garage_1");

        service.processWebhook(body, sign(body), WEBHOOK_URL);

        verify(userFeatureService).revokeFeature(99L, AppFeature.GARAGE);
    }

    @Test
    void subscriptionCanceledDisambiguatesByPlanIdWhenCustomerHasMultipleSubscriptions() {
        // Same customer bought two separate plans (Garage + Vault) rather than the Suite —
        // squareCustomerId alone doesn't uniquely identify which one this event is about.
        Subscription garageSub = new Subscription();
        garageSub.setSquareSubscriptionId("sub_garage");
        garageSub.setSquareCustomerId("cust_456");
        garageSub.setUserId(42L);
        garageSub.setPlanId("plan_garage_1");
        garageSub.setStatus(SubscriptionStatus.ACTIVE);

        Subscription vaultSub = new Subscription();
        vaultSub.setSquareSubscriptionId("sub_vault");
        vaultSub.setSquareCustomerId("cust_456");
        vaultSub.setUserId(42L);
        vaultSub.setPlanId("plan_vault_1");
        vaultSub.setStatus(SubscriptionStatus.ACTIVE);

        when(subscriptionRepo.findBySquareSubscriptionId("sub_garage")).thenReturn(Optional.empty());
        when(subscriptionRepo.findBySquareCustomerId("cust_456")).thenReturn(List.of(garageSub, vaultSub));
        when(subscriptionRepo.findBySquareCustomerIdAndPlanId("cust_456", "plan_garage_1"))
                .thenReturn(List.of(garageSub));

        // Event's squareSubscriptionId ("sub_garage") doesn't match any local row by ID (simulating
        // the row-missing/race case this fallback exists for), forcing resolution through customer ID.
        String body = subscriptionEventBody("subscription.canceled", "sub_garage", "cust_456", "plan_garage_1");

        service.processWebhook(body, sign(body), WEBHOOK_URL);

        verify(userFeatureService).revokeFeature(42L, AppFeature.GARAGE);
        verify(userFeatureService, never()).revokeFeature(any(), eq(AppFeature.DOCUMENT_VAULT));
    }

    @Test
    void subscriptionCanceledDropsEventWhenCustomerHasAmbiguousSubscriptionsAndPlanDoesNotDisambiguate() {
        // Customer canceled and re-subscribed to the same plan, leaving two rows with an
        // identical planId — plan-based disambiguation can't narrow it to one, so the event
        // must be dropped rather than guessing which row (and therefore which feature) to revoke.
        Subscription first = new Subscription();
        first.setSquareSubscriptionId("sub_first");
        first.setSquareCustomerId("cust_456");
        first.setUserId(42L);
        first.setPlanId("plan_garage_1");
        first.setStatus(SubscriptionStatus.CANCELED);

        Subscription second = new Subscription();
        second.setSquareSubscriptionId("sub_second");
        second.setSquareCustomerId("cust_456");
        second.setUserId(42L);
        second.setPlanId("plan_garage_1");
        second.setStatus(SubscriptionStatus.ACTIVE);

        when(subscriptionRepo.findBySquareSubscriptionId("sub_unknown")).thenReturn(Optional.empty());
        when(subscriptionRepo.findBySquareCustomerId("cust_456")).thenReturn(List.of(first, second));
        when(subscriptionRepo.findBySquareCustomerIdAndPlanId("cust_456", "plan_garage_1"))
                .thenReturn(List.of(first, second));

        String body = subscriptionEventBody("subscription.canceled", "sub_unknown", "cust_456", "plan_garage_1");

        service.processWebhook(body, sign(body), WEBHOOK_URL);

        verify(userFeatureService, never()).revokeFeature(any(), any());
        verify(subscriptionRepo, never()).save(any());
    }

    @Test
    void unknownSubscriptionDropsEventWithoutError() {
        when(subscriptionRepo.findBySquareSubscriptionId("sub_999")).thenReturn(Optional.empty());
        when(subscriptionRepo.findBySquareCustomerId("cust_999")).thenReturn(java.util.List.of());

        String body = subscriptionEventBody("subscription.canceled", "sub_999", "cust_999", "plan_garage_1");

        service.processWebhook(body, sign(body), WEBHOOK_URL);

        verify(userFeatureService, never()).revokeFeature(any(), any());
        verify(subscriptionRepo, never()).save(any());
    }

    @Test
    void invalidSignatureIsIgnored() {
        String body = subscriptionEventBody("subscription.canceled", "sub_123", "cust_456", "plan_garage_1");

        service.processWebhook(body, "not-a-valid-signature", WEBHOOK_URL);

        verifyNoInteractions(userFeatureService);
    }

    @Test
    void subscriptionUpdatedActiveGrantsFeatureAndExtendsCurrentPeriodEnd() {
        Subscription localSub = new Subscription();
        localSub.setSquareSubscriptionId("sub_123");
        localSub.setUserId(42L);
        localSub.setPlanId("plan_garage_1");
        localSub.setStatus(SubscriptionStatus.PAST_DUE);
        when(subscriptionRepo.findBySquareSubscriptionId("sub_123")).thenReturn(Optional.of(localSub));

        String body = subscriptionStatusEventBody("subscription.updated", "sub_123", "cust_456", "ACTIVE", "2026-08-04");

        service.processWebhook(body, sign(body), WEBHOOK_URL);

        verify(userFeatureService).grantFeature(42L, AppFeature.GARAGE);

        ArgumentCaptor<Subscription> saved = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepo).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(saved.getValue().getGracePeriodEndsAt()).isNull();
        assertThat(saved.getValue().getCurrentPeriodEnd()).isEqualTo(java.time.LocalDate.of(2026, 8, 4).atStartOfDay());
    }

    @Test
    void subscriptionUpdatedCanceledStatusRevokesFeature() {
        Subscription localSub = new Subscription();
        localSub.setSquareSubscriptionId("sub_123");
        localSub.setUserId(42L);
        localSub.setPlanId("plan_garage_1");
        localSub.setStatus(SubscriptionStatus.ACTIVE);
        when(subscriptionRepo.findBySquareSubscriptionId("sub_123")).thenReturn(Optional.of(localSub));

        String body = subscriptionStatusEventBody("subscription.updated", "sub_123", "cust_456", "CANCELED", null);

        service.processWebhook(body, sign(body), WEBHOOK_URL);

        verify(userFeatureService).revokeFeature(42L, AppFeature.GARAGE);
    }

    @Test
    void subscriptionUpdatedWithoutStatusFieldGrantsNothing() {
        Subscription localSub = new Subscription();
        localSub.setSquareSubscriptionId("sub_123");
        localSub.setUserId(42L);
        localSub.setPlanId("plan_garage_1");
        localSub.setStatus(SubscriptionStatus.ACTIVE);
        when(subscriptionRepo.findBySquareSubscriptionId("sub_123")).thenReturn(Optional.of(localSub));

        // No "status" field at all — old code implicitly granted on every subscription.updated;
        // Session 2 requires reading the real status instead of assuming ACTIVE.
        String body = subscriptionEventBody("subscription.updated", "sub_123", "cust_456", "plan_garage_1");

        service.processWebhook(body, sign(body), WEBHOOK_URL);

        verifyNoInteractions(userFeatureService);
        verify(subscriptionRepo, never()).save(any());
    }

    @Test
    void invoicePaymentMadeClearsGracePeriodAndGrantsFeature() {
        Subscription localSub = new Subscription();
        localSub.setSquareSubscriptionId("sub_123");
        localSub.setUserId(42L);
        localSub.setPlanId("plan_garage_1");
        localSub.setStatus(SubscriptionStatus.PAST_DUE);
        localSub.setGracePeriodEndsAt(LocalDateTime.now().plusDays(3));
        when(subscriptionRepo.findBySquareSubscriptionId("sub_123")).thenReturn(Optional.of(localSub));

        String body = invoiceEventBody("invoice.payment_made", "sub_123");

        service.processWebhook(body, sign(body), WEBHOOK_URL);

        verify(userFeatureService).grantFeature(42L, AppFeature.GARAGE);
        ArgumentCaptor<Subscription> saved = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepo).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(saved.getValue().getGracePeriodEndsAt()).isNull();
    }

    @Test
    void invoiceScheduledChargeFailedSetsPastDueWithoutRevokingAccess() {
        Subscription localSub = new Subscription();
        localSub.setSquareSubscriptionId("sub_123");
        localSub.setUserId(42L);
        localSub.setPlanId("plan_garage_1");
        localSub.setStatus(SubscriptionStatus.ACTIVE);
        when(subscriptionRepo.findBySquareSubscriptionId("sub_123")).thenReturn(Optional.of(localSub));

        String body = invoiceEventBody("invoice.scheduled_charge_failed", "sub_123");

        service.processWebhook(body, sign(body), WEBHOOK_URL);

        verifyNoInteractions(userFeatureService); // access continues through the grace window
        ArgumentCaptor<Subscription> saved = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepo).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(SubscriptionStatus.PAST_DUE);
        assertThat(saved.getValue().getGracePeriodEndsAt()).isAfter(LocalDateTime.now().plusDays(8));
    }

    @Test
    void invoicePaymentFailedAliasIsAlsoHandled() {
        Subscription localSub = new Subscription();
        localSub.setSquareSubscriptionId("sub_123");
        localSub.setUserId(42L);
        localSub.setPlanId("plan_garage_1");
        localSub.setStatus(SubscriptionStatus.ACTIVE);
        when(subscriptionRepo.findBySquareSubscriptionId("sub_123")).thenReturn(Optional.of(localSub));

        String body = invoiceEventBody("invoice.payment_failed", "sub_123");

        service.processWebhook(body, sign(body), WEBHOOK_URL);

        ArgumentCaptor<Subscription> saved = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepo).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(SubscriptionStatus.PAST_DUE);
    }

    @Test
    void alreadyCanceledSubscriptionIgnoresPaymentFailedEvent() {
        Subscription localSub = new Subscription();
        localSub.setSquareSubscriptionId("sub_123");
        localSub.setUserId(42L);
        localSub.setPlanId("plan_garage_1");
        localSub.setStatus(SubscriptionStatus.CANCELED);
        when(subscriptionRepo.findBySquareSubscriptionId("sub_123")).thenReturn(Optional.of(localSub));

        String body = invoiceEventBody("invoice.scheduled_charge_failed", "sub_123");

        service.processWebhook(body, sign(body), WEBHOOK_URL);

        verify(subscriptionRepo, never()).save(any());
    }

    @Test
    void runGracePeriodSweepRevokesExpiredPastDueSubscriptionsIndependently() {
        Subscription expired = new Subscription();
        expired.setSquareSubscriptionId("sub_expired");
        expired.setUserId(42L);
        expired.setPlanId("plan_garage_1");
        expired.setStatus(SubscriptionStatus.PAST_DUE);
        expired.setGracePeriodEndsAt(LocalDateTime.now().minusDays(1));

        when(subscriptionRepo.findByStatusAndGracePeriodEndsAtBefore(eq(SubscriptionStatus.PAST_DUE), any()))
                .thenReturn(List.of(expired));

        service.runGracePeriodSweep();

        verify(userFeatureService).revokeFeature(42L, AppFeature.GARAGE);
        assertThat(expired.getStatus()).isEqualTo(SubscriptionStatus.CANCELED);
        assertThat(expired.getGracePeriodEndsAt()).isNull();
        verify(subscriptionRepo).save(expired);
    }

    @Test
    void runGracePeriodSweepSkipsWhenSquareNotConfigured() {
        when(configRepo.findFirstBy()).thenReturn(Optional.empty());

        service.runGracePeriodSweep();

        verifyNoInteractions(subscriptionRepo);
        verifyNoInteractions(userFeatureService);
    }

    // ── Session 3: reconciliation ────────────────────────────────────────────

    @Test
    void reconciliationCorrectsMissedCancellationAndRevokesFeature() {
        Subscription local = new Subscription();
        local.setId(1L);
        local.setSquareSubscriptionId("sub_123");
        local.setUserId(42L);
        local.setPlanId("plan_garage_1");
        local.setStatus(SubscriptionStatus.ACTIVE);
        when(subscriptionRepo.findByStatusIn(List.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE)))
                .thenReturn(List.of(local));
        when(restTemplate.exchange(eq("https://connect.squareupsandbox.com/v2/subscriptions/sub_123"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(squareResponse(Map.of("id", "sub_123", "status", "CANCELED")));

        service.reconcileSubscriptions();

        verify(userFeatureService).revokeFeature(42L, AppFeature.GARAGE);
        assertThat(local.getStatus()).isEqualTo(SubscriptionStatus.CANCELED);
        verify(subscriptionRepo).save(local);
    }

    @Test
    void reconciliationLeavesPastDueUntouchedWhenRemoteStillActive() {
        // Square's subscription status stays ACTIVE through the whole dunning window — this must
        // NOT clear a legitimate PAST_DUE grace period; only the invoice.* webhooks / grace sweep own that.
        Subscription local = new Subscription();
        local.setSquareSubscriptionId("sub_123");
        local.setUserId(42L);
        local.setPlanId("plan_garage_1");
        local.setStatus(SubscriptionStatus.PAST_DUE);
        local.setGracePeriodEndsAt(LocalDateTime.now().plusDays(3));
        when(subscriptionRepo.findByStatusIn(List.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE)))
                .thenReturn(List.of(local));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(squareResponse(Map.of("id", "sub_123", "status", "ACTIVE", "charged_through_date", "2026-09-01")));

        service.reconcileSubscriptions();

        verifyNoInteractions(userFeatureService);
        verify(subscriptionRepo, never()).save(any());
        assertThat(local.getStatus()).isEqualTo(SubscriptionStatus.PAST_DUE);
        assertThat(local.getGracePeriodEndsAt()).isNotNull();
    }

    @Test
    void reconciliationExtendsCurrentPeriodEndWhenAlreadyActive() {
        Subscription local = new Subscription();
        local.setSquareSubscriptionId("sub_123");
        local.setUserId(42L);
        local.setPlanId("plan_garage_1");
        local.setStatus(SubscriptionStatus.ACTIVE);
        when(subscriptionRepo.findByStatusIn(List.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE)))
                .thenReturn(List.of(local));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(squareResponse(Map.of("id", "sub_123", "status", "ACTIVE", "charged_through_date", "2026-09-01")));

        service.reconcileSubscriptions();

        verifyNoInteractions(userFeatureService);
        assertThat(local.getCurrentPeriodEnd()).isEqualTo(LocalDate.of(2026, 9, 1).atStartOfDay());
        verify(subscriptionRepo).save(local);
    }

    @Test
    void reconciliationOneBadRowDoesNotAbortTheSweep() {
        Subscription broken = new Subscription();
        broken.setSquareSubscriptionId("sub_broken");
        broken.setUserId(1L);
        broken.setPlanId("plan_garage_1");
        broken.setStatus(SubscriptionStatus.ACTIVE);

        Subscription healthy = new Subscription();
        healthy.setSquareSubscriptionId("sub_healthy");
        healthy.setUserId(2L);
        healthy.setPlanId("plan_garage_1");
        healthy.setStatus(SubscriptionStatus.ACTIVE);

        when(subscriptionRepo.findByStatusIn(List.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE)))
                .thenReturn(List.of(broken, healthy));
        when(restTemplate.exchange(eq("https://connect.squareupsandbox.com/v2/subscriptions/sub_broken"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new RuntimeException("network blip"));
        when(restTemplate.exchange(eq("https://connect.squareupsandbox.com/v2/subscriptions/sub_healthy"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(squareResponse(Map.of("id", "sub_healthy", "status", "CANCELED")));

        service.reconcileSubscriptions();

        verify(userFeatureService).revokeFeature(2L, AppFeature.GARAGE);
        verify(userFeatureService, never()).revokeFeature(eq(1L), any());
    }

    @Test
    void reconciliationSkipsWhenSquareNotConfigured() {
        when(configRepo.findFirstBy()).thenReturn(Optional.empty());

        service.reconcileSubscriptions();

        verifyNoInteractions(subscriptionRepo);
        verifyNoInteractions(userFeatureService);
    }

    // ── Session 3: self-service cancel ───────────────────────────────────────

    @Test
    void cancelSubscriptionSchedulesCancellationWithoutRevokingAccess() {
        User user = new User();
        user.setId(42L);
        user.setEmail("owner@example.com");
        mockSecurityContext(user);

        Subscription sub = new Subscription();
        sub.setId(7L);
        sub.setSquareSubscriptionId("sub_123");
        sub.setUserId(42L);
        sub.setPlanId("plan_garage_1");
        sub.setStatus(SubscriptionStatus.ACTIVE);
        when(subscriptionRepo.findById(7L)).thenReturn(Optional.of(sub));
        when(restTemplate.exchange(eq("https://connect.squareupsandbox.com/v2/subscriptions/sub_123/cancel"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(squareResponse(Map.of("id", "sub_123", "status", "ACTIVE", "canceled_date", "2026-08-04")));

        var dto = service.cancelSubscription(7L);

        verifyNoInteractions(userFeatureService);
        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(sub.getCancelEffectiveDate()).isEqualTo(LocalDate.of(2026, 8, 4));
        assertThat(dto.getCancelEffectiveDate()).isEqualTo(LocalDate.of(2026, 8, 4));
        verify(subscriptionRepo).save(sub);
    }

    @Test
    void cancelSubscriptionRejectsNonOwner() {
        User user = new User();
        user.setId(99L);
        user.setEmail("intruder@example.com");
        mockSecurityContext(user);

        Subscription sub = new Subscription();
        sub.setId(7L);
        sub.setSquareSubscriptionId("sub_123");
        sub.setUserId(42L);
        sub.setStatus(SubscriptionStatus.ACTIVE);
        when(subscriptionRepo.findById(7L)).thenReturn(Optional.of(sub));

        assertThatThrownBy(() -> service.cancelSubscription(7L))
                .hasMessageContaining("Access denied");
        verifyNoInteractions(restTemplate);
    }

    @Test
    void cancelSubscriptionRejectsAlreadyCanceled() {
        User user = new User();
        user.setId(42L);
        user.setEmail("owner@example.com");
        mockSecurityContext(user);

        Subscription sub = new Subscription();
        sub.setId(7L);
        sub.setSquareSubscriptionId("sub_123");
        sub.setUserId(42L);
        sub.setStatus(SubscriptionStatus.CANCELED);
        when(subscriptionRepo.findById(7L)).thenReturn(Optional.of(sub));

        assertThatThrownBy(() -> service.cancelSubscription(7L))
                .hasMessageContaining("already canceled");
        verifyNoInteractions(restTemplate);
    }

    private String subscriptionEventBody(String type, String subId, String customerId, String planId) {
        return "{"
                + "\"type\":\"" + type + "\","
                + "\"data\":{\"object\":{\"subscription\":{"
                + "\"id\":\"" + subId + "\","
                + "\"customer_id\":\"" + customerId + "\","
                + "\"plan_variation_id\":\"" + planId + "\""
                + "}}}}";
    }

    private String subscriptionStatusEventBody(String type, String subId, String customerId,
                                                String status, String chargedThroughDate) {
        String chargedThroughField = chargedThroughDate == null
                ? ""
                : ",\"charged_through_date\":\"" + chargedThroughDate + "\"";
        return "{"
                + "\"type\":\"" + type + "\","
                + "\"data\":{\"object\":{\"subscription\":{"
                + "\"id\":\"" + subId + "\","
                + "\"customer_id\":\"" + customerId + "\","
                + "\"status\":\"" + status + "\""
                + chargedThroughField
                + "}}}}";
    }

    private String invoiceEventBody(String type, String subscriptionId) {
        return "{"
                + "\"type\":\"" + type + "\","
                + "\"data\":{\"object\":{\"invoice\":{"
                + "\"id\":\"inv_1\","
                + "\"subscription_id\":\"" + subscriptionId + "\""
                + "}}}}";
    }

    private String sign(String body) {
        try {
            String payload = WEBHOOK_URL + body;
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(SIGNING_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            byte[] hmac = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hmac);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
