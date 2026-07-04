# TODO — Subscription Billing (Square)

## Goal
Turn the existing partial Square subscription plumbing into a real billing system:
persisted subscription state, correct webhook lifecycle handling, a grace-period
lockout on failed payments, and periodic reconciliation. Payment processor decision
is final: **Square** (already partially integrated; cheaper than Stripe — no separate
"billing" fee layer on top of the 2.9%+$0.30 processing rate; avoids building a second
vendor integration from scratch).

## Confirmed decisions (don't re-derive / re-litigate)
- Processor: **Square**, not Stripe. Rejected Stripe because (a) it adds a 0.5–0.8%
  Billing-product fee on top of processing, recurring forever, and (b) Square
  subscription code already exists in this repo — starting fresh with Stripe would
  mean building and maintaining two vendor integrations during any future migration.
- Lockout behavior on payment failure: **grace period, then hard lock** (not
  immediate lock, not read-only fallback). Exact grace window (days) not yet decided
  — pick during Session 1 (Square's own dunning retries typically span ~3-7 days;
  align the grace window with however many retry attempts Square schedules so we
  don't lock a user out mid-retry).
- Feature gating stays anchored in `FeatureEntitlementService` /
  `UserFeatureGrant` / `OrgFeature` — those tables remain the actual access-control
  source of truth. The new `Subscription` entity feeds them; it does not replace them.
- Org-only gating for Vault/Garage/Jobs/Sharing/Shop still applies (see
  `[[project-feature-entitlements]]` memory) — this work is about *how* an
  `OrgFeature`/`UserFeatureGrant` gets granted/revoked correctly, not about changing
  which features require an org.

## Current-state snapshot (as of 2026-07-04, pre-Session-1 — STALE, kept for history only)
Everything below this line describes the state *before* Session 1/2. Both are now
done — see the "DONE" entries under Session 1 and Session 2 in the checklist below
for what actually changed and where. Re-read the checklist, not this snapshot, when
resuming Session 3.
- `service/SubscriptionService.java` (331 lines): real Square API calls for
  customer search/create, card tokenize, subscription create. Webhook handler
  (`processWebhook`, lines 83-124) only handles `subscription.created/updated`,
  `payment.completed` (→ grant) and `subscription.canceled/deactivated` (→ revoke).
  No `invoice.payment_failed` / `invoice.scheduled_charge_failed` handling at all.
- **Known bug**: `extractCustomerEmail` (lines 262-278) reads
  `object.subscription.customer_email`, but Square's subscription webhook payloads
  only carry `customer_id`, not an email. Subscription lifecycle events (created/
  updated/canceled) likely fail to resolve a user and get silently dropped — only
  `payment.completed` (which does carry buyer email) reliably works today. This
  means cancellations probably don't currently revoke access.
- No `Subscription`/`UserSubscription` entity exists. Only persisted state is the
  binary `UserFeatureGrant` / `OrgFeature` (org/user + feature + `expiresAt`,
  perpetual/null by default). No Square subscription ID, no period-end date, no
  status, no payment history is stored anywhere.
- `controller/SquareWebhookController.java`: signature-verified, gated behind
  `square.webhook.enabled` (default false), always returns HTTP 200 (correct per
  Square retry semantics).
- `controller/SubscriptionController.java`: only `GET /square-config` and
  `POST /square` (purchase). No list/cancel/status endpoint for users.
- `model/PlatformSquareConfig.java`: singleton config row (encrypted token, app id,
  location id, encrypted webhook signing key, environment, 4 plan-ID fields for
  garage/vault/jobs/suite). No per-user/org subscription record.
- `FeatureEntitlementService.java`: never touches Square or payment status directly;
  only reads `UserFeatureGrant`/`OrgFeature`. `features.gating.enabled=false` in
  local/local-mysql profiles (mock dev user, no gating).
- No `@Scheduled` job touches billing anywhere (existing `@Scheduled` jobs are all
  in the immigration module — `FormVersionScheduler` etc. — good pattern to copy).

## Session checklist

### Session 1 — Data model + fix the broken revocation path — DONE 2026-07-04
- [x] Add `Subscription` entity: `squareSubscriptionId`, `squareCustomerId`,
      `userId`/`orgId` (loose FK, no JPA relation — matches app-splitting FK
      convention), `feature`/`plan`, `status` enum (`ACTIVE`, `PAST_DUE`,
      `CANCELED`, `PAUSED`), `currentPeriodEnd`, `gracePeriodEndsAt`,
      `lastPaymentStatus`, timestamps.
      → `model/Subscription.java` + `model/SubscriptionStatus.java` +
      `repository/SubscriptionRepository.java` (`findBySquareSubscriptionId`,
      `findBySquareCustomerId`). `purchaseSubscription()` now saves this row
      right after Square's subscription-create call succeeds (captures the
      real `squareSubscriptionId`/status/`charged_through_date` from the
      response — previously discarded).
- [x] Fix `extractCustomerEmail`: resolve user by stored `squareCustomerId`
      (set at subscription-creation time in `SubscriptionService`), not by parsing
      email out of webhook payloads.
      → Replaced with `resolveUserId(event, eventType)`: `payment.completed`
      still resolves via `buyer_email_address` (that path already worked);
      all `subscription.*` events now resolve by looking up the local
      `Subscription` row via `squareSubscriptionId` first, falling back to
      `squareCustomerId`. `subscription.canceled/deactivated` also flips the
      local row to `CANCELED` (`markSubscriptionCanceled`).
- [x] Decide and hardcode the grace-period length (days) after checking Square's
      actual dunning retry schedule for the configured environment.
      → **9 days** (`SubscriptionService.GRACE_PERIOD_DAYS`). Square retries a
      declined subscription charge on day 3, 6, and 9 after the decline, then
      stops — see [Square Community: Automatic Payment Retries](https://community.squareup.com/t5/Archived-Articles-Read-Only/New-Automatic-Payment-Retries-with-Square-Subscriptions/ba-p/343646).
      Not yet wired to any lockout logic — that's Session 2 (`invoice.payment_failed`
      handling + the daily sweep job).
- [x] Write/update tests confirming a `subscription.canceled` webhook now actually
      resolves the user and revokes the `OrgFeature`/`UserFeatureGrant`.
      → `test/service/SubscriptionServiceTest.java` (4 tests): resolves via
      squareSubscriptionId, falls back to squareCustomerId, drops unknown
      subscriptions without error, ignores invalid signatures. Full suite:
      184 tests, same 5 pre-existing failures in `FeatureEntitlementServiceTest`
      (unrelated — present on `main` before this session, not touched here).

### Session 2 — Webhook lifecycle expansion + grace-period lockout — DONE 2026-07-04
- [x] Handle `invoice.payment_made` → `Subscription.status = ACTIVE`, extend
      `currentPeriodEnd`.
      → `SubscriptionService.handleInvoicePaymentMade()`: resolves the local
      `Subscription` via `invoice.subscription_id` (subscription-billed invoices
      always carry this per Square's docs), clears `gracePeriodEndsAt`, sets
      `lastPaymentStatus="PAID"`, re-grants the feature. Does NOT set
      `currentPeriodEnd` here — this payload doesn't reliably carry it; the
      `subscription.updated` event Square fires right after a successful charge
      is the authoritative source for `charged_through_date` (see next item).
- [x] Handle `invoice.payment_failed` / `scheduled_charge_failed` → `status =
      PAST_DUE`, set `gracePeriodEndsAt = now + <decided days>`. Do NOT revoke
      `OrgFeature`/`UserFeatureGrant` yet at this point — access continues through
      the grace window.
      → `SubscriptionService.handleInvoicePaymentFailed()`. Verified the real
      Square event name is `invoice.scheduled_charge_failed` (there is no
      documented `invoice.payment_failed`); both are handled defensively in the
      same switch case. No-ops if the subscription is already CANCELED.
- [x] Stop treating every `subscription.updated` as an implicit grant — read the
      actual status field on the payload and branch accordingly.
      → `SubscriptionService.handleSubscriptionStatusEvent()` (shared by
      `subscription.created`/`subscription.updated`): reads `object.subscription.status`
      (ACTIVE/CANCELED/DEACTIVATED/PAUSED/other) and branches — ACTIVE grants +
      extends `currentPeriodEnd` from `charged_through_date`; CANCELED/DEACTIVATED
      revokes; PAUSED just records status (feature grant left untouched — pausing
      is a merchant/customer choice, not a billing failure); unknown/missing
      status logs and no-ops instead of granting. Dedicated `subscription.canceled`/
      `subscription.deactivated` event types still go through a separate
      unconditional-revoke handler (`handleSubscriptionCanceled`) since those event
      types mean "canceled" regardless of whether a status field is present.
- [x] New `@Scheduled` daily job (mirror `FormVersionScheduler` pattern): find
      `Subscription` rows with `status=PAST_DUE` and `gracePeriodEndsAt < now`,
      flip to `CANCELED`, revoke the linked feature grant.
      → `SubscriptionLockoutScheduler` (`@Scheduled(cron = "0 0 2 * * ?")`, daily
      2am) delegates to `SubscriptionService.runGracePeriodSweep()`. Each row
      processed in its own try/catch so one bad row can't abort the sweep, same
      isolation pattern as `FormVersionScheduler`/`checkForUpdates()`.
- [x] Frontend: banner component shown when `status=PAST_DUE` — "payment failed,
      update your card by {gracePeriodEndsAt}" — needs a way to surface this to
      the Angular app (new field on `GET /api/me/features` response, or a new
      `GET /api/subscriptions/mine` endpoint — decide during this session).
      → Decided: new `GET /api/subscriptions/mine` endpoint (`SubscriptionDTO`:
      id, planId, status, currentPeriodEnd, gracePeriodEndsAt) rather than
      changing `/api/me/features`'s shape (that response is a plain `string[]`
      consumed in several places — changing it would ripple). Frontend:
      `SubscriptionService.getMine()` (swallows errors to `[]` — must never break
      the app shell) + new shell component `PastDueBannerComponent`
      (`components/past-due-banner/`), declared in `AppModule` alongside
      `WelcomeBannerComponent`/`PlansComponent`, rendered inside `.main-content`
      in `app.component.html` (inherits the mobile fixed-header clearance for
      free). Bootstrap `alert alert-danger` + `bi-exclamation-triangle-fill`,
      matching the existing alert convention elsewhere in the app; links to
      `/plans` to fix payment; session-only dismiss (re-shows on reload while
      still PAST_DUE — intentional, this is a billing issue, not a one-time tip).
- New tests: 9 added to `SubscriptionServiceTest` (22 total) covering
  invoice.payment_made, invoice.scheduled_charge_failed/payment_failed alias,
  already-canceled no-op, subscription.updated ACTIVE/CANCELED/no-status-field
  branching, and the grace-period sweep (expired row revoked + not-configured
  no-op). Full suite: 189 tests, same 5 pre-existing `FeatureEntitlementServiceTest`
  failures (unrelated, present on `main` before Session 1).

### Session 3 — Reconciliation + user-facing self-service — DONE 2026-07-04
- [x] New `@Scheduled` job: periodically call Square `GET /subscriptions/{id}`
      for any `Subscription` row in `ACTIVE`/`PAST_DUE` state to correct drift if
      a webhook was ever missed (network blip, retry exhaustion).
      → `SubscriptionReconciliationScheduler` (daily 01:30 AM, before the 02:00 AM
      lockout sweep) delegates to `SubscriptionService.reconcileSubscriptions()`,
      each row isolated in its own try/catch. Deliberately conservative: Square's
      subscription-level status stays ACTIVE through the whole dunning retry
      window (invoice failures don't change it), so an ACTIVE remote status never
      clears a local PAST_DUE grace period — that stays owned by the invoice.*
      webhooks / grace sweep. Only CANCELED/DEACTIVATED/PAUSED are treated as
      authoritative drift (a genuinely missed cancellation webhook); ACTIVE only
      opportunistically extends `currentPeriodEnd` when already ACTIVE locally.
      Branching logic factored into `applyReconciledStatus()` so it's unit
      testable without mocking the Square HTTP call.
- [x] ~~`GET /api/subscriptions/mine` — status, renewal date, grace deadline.~~
      Built early, in Session 2 — the past-due banner needed it. Already returns
      `SubscriptionDTO[]` (id, planId, status, currentPeriodEnd, gracePeriodEndsAt)
      for the current user via `SubscriptionController.getMine()`. Nothing left to
      do here unless the DTO needs more fields for self-service cancel UI below.
- [x] `POST /api/subscriptions/{id}/cancel` — user-initiated cancellation, calls
      Square's cancel API, sets local status accordingly.
      → Decided (user confirmed): access continues until the current paid period
      ends, not immediate lockout — matches Square's own cancel semantics (Square
      schedules cancellation for end of billing period; the existing
      `subscription.canceled` webhook fires then and does the actual revoke, same
      as any other cancellation path). `SubscriptionService.cancelSubscription()`
      validates ownership + not-already-canceled, calls Square's
      `POST /subscriptions/{id}/cancel`, and stores the returned `canceled_date`
      on a new `Subscription.cancelEffectiveDate` field (nullable, Hibernate
      `ddl-auto=update` adds it automatically) — status/feature access
      untouched here. `SubscriptionDTO` gained `cancelEffectiveDate`. Frontend:
      `SubscriptionService.cancel(id)` + `/plans` page now loads `getMine()`,
      matches each plan card's Square plan ID to its `MySubscription` row, and
      shows a "Cancel Plan" button (→ "Cancels {date}" once scheduled) under the
      existing "Active" badge.
- [ ] End-to-end manual test in Square sandbox: full cycle of purchase → simulated
      failed renewal → grace banner appears → grace window expires → feature
      locks out → `/locked/:feature` page shown.
      → **Not done — requires a human with sandbox dashboard access to trigger a
      real declined-then-retried charge; not something achievable from code.**
      Manual steps for whoever runs this: (1) subscribe via `/plans` in sandbox
      mode with a Square test card, (2) use the Square sandbox dashboard to force
      a subscription invoice to fail, (3) confirm `PastDueBannerComponent` shows
      up on next page load and `/api/subscriptions/mine` reports PAST_DUE +
      gracePeriodEndsAt, (4) manually back-date `subscriptions.grace_period_ends_at`
      in the DB to simulate expiry, run `SubscriptionLockoutScheduler` (or wait for
      the 2am cron), confirm the `UserFeatureGrant` row is deleted and the gated
      feature route redirects to `/locked/:feature`.

**Session 3 tests**: 9 new (`SubscriptionServiceTest` now 21 total, up from 12) —
reconciliation: missed-cancellation drift corrected + feature revoked, PAST_DUE
left untouched when Square still reports ACTIVE (dunning window), currentPeriodEnd
extension when already ACTIVE, one-bad-row isolation, skip when unconfigured;
cancel: schedules cancellation without touching access/status, rejects
non-owner, rejects already-canceled. Full suite: 197 tests, same 5 pre-existing
`FeatureEntitlementServiceTest` failures (unrelated, present before Session 1).
Frontend (`npm run build`) compiles clean.

### Session 4 — Verification audit + customer-ID fallback disambiguation — DONE 2026-07-04
- [x] Verify Sessions 1-3 actually work as claimed (re-read code, don't trust prose) — no
      code changes, audit only. Confirmed all claims match the actual implementation;
      197 tests passing (5 pre-existing unrelated `FeatureEntitlementServiceTest`
      failures — missing `@Mock UserFeatureRepository` in that test fixture); frontend
      build clean. Found one residual gap (below) and fixed it same session.
- [x] Fix `findLocalSubscription()`'s customer-ID fallback: it fell back to
      `findBySquareCustomerId(...).stream().findFirst()` with no disambiguation. A single
      Square customer can legitimately have 2+ local `Subscription` rows (e.g. bought
      Garage and Vault separately instead of the Suite) — if a `subscription.*` webhook's
      `squareSubscriptionId` lookup ever missed, the old fallback could silently grab the
      *wrong* row and grant/revoke the wrong feature.
      → `SubscriptionService.findLocalSubscription()` now takes a `planId` parameter
      (the webhook's `plan_variation_id`, already read by `handleSubscriptionStatusEvent`/
      `handleSubscriptionCanceled` — extracted inline, no new payload parsing). When the
      customer-ID lookup returns more than one row, it's narrowed via new
      `SubscriptionRepository.findBySquareCustomerIdAndPlanId(customerId, planId)`. If
      that still doesn't resolve to exactly one row (e.g. same plan repurchased after a
      prior cancellation, leaving two rows with identical `planId`), the event is
      **dropped with a WARN log listing the candidate subscription IDs** rather than
      guessing — reconciliation catches genuine drift on its next daily pass, which is
      safer than acting on the wrong row.
      → `handleInvoicePaymentMade`/`handleInvoicePaymentFailed` were not touched — they
      never call `findLocalSubscription`; they resolve directly via
      `subscription_id` on the invoice payload, which Square always includes for
      subscription-billed invoices, so there's no fallback-ambiguity risk there.
      → 2 new tests in `SubscriptionServiceTest` (23 total, up from 21):
      `subscriptionCanceledDisambiguatesByPlanIdWhenCustomerHasMultipleSubscriptions`
      (two rows, different plans, correct one picked) and
      `subscriptionCanceledDropsEventWhenCustomerHasAmbiguousSubscriptionsAndPlanDoesNotDisambiguate`
      (two rows, same plan, event dropped, nothing revoked). Full suite: 199 tests, same
      5 pre-existing unrelated failures. Frontend unaffected (backend-only change).

## Not in scope for this TODO
- Deciding personal bank account vs. LLC for the Square merchant account
  (separate business/legal decision, not a code question).
- Stripe integration of any kind — direction is finalized on Square.
