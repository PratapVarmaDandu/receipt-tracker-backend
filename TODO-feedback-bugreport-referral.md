# TODO — Feedback / Bug Report / Idea Rewards / Referral Program

Planning only — no code written yet. This file is the resumable source of truth;
don't re-derive from chat history when picking this back up.

## Goal
Five requested features, all sharing one underlying "user submission → email →
platform-owner review" pipeline, plus a separate referral-rewards flow that reuses
the same "grant bonus subscription time" primitive as the idea reward:

1. **Feedback form** — any authenticated user, optional attachment ≤ 5MB, auto-attaches
   a diagnostic log, emails platform owner for reference.
2. **Report a bug** — same pipeline, attachment restricted to screenshots (images only),
   ≤ 5MB, auto-attaches diagnostic log.
3. **Submit an idea** — same pipeline, no size/type restriction stated; reward = 1 month
   free subscription on submission.
4. **Referral program** — existing user refers a new user; referrer gets 1 month free,
   stacking on top of whatever they already have (12 → 13, 13 → 14, uncapped per the
   spec as given).
5. **Platform owner review console** — new tab in the existing `/platform` admin area
   listing all feedback/bug/idea submissions with attachment preview, submitter name+email.

## Open decisions — confirm before/while implementing, don't silently assume
These are genuinely ambiguous from the request and materially change the design:

- **"Auto-capture log" — what log?** Assumption used below: client-side diagnostic
  info (recent browser console errors/warnings, last few failed API calls — status+URL
  only, no payloads, to avoid leaking sensitive data — userAgent, current route, app
  build/commit if available). Not server-side application logs (those aren't scoped to
  a single user action and would need a correlation ID scheme that doesn't exist today).
- **Idea/bug/feedback: one combined widget or three separate entry points?** Recommend
  one global trigger (FAB) with a 3-way type selector inside, backed by one entity —
  cuts UI and backend surface to 1/3. The request lists them as 3 bullets but nothing
  says they need separate buttons.
- **Idea reward: automatic on every submission, or after platform-owner approval?**
  **Resolved in Session 3: after platform-admin review, for any submission type**
  (feedback/bug/idea, not just idea) — admin manually grants a 1-month bonus for a
  feature of their choosing after reading the submission. This closes the farming
  concern outright (a human gates every grant) rather than needing a rate-limit rule.
- **Referral reward trigger: new user signup, or new user's first paid conversion?**
  **Resolved in Session 4**: fires on the referred account's genuine first OAuth login
  (not just clicking the link) — enforced server-side via a one-shot HttpSession flag
  (`NewSignupFlag`), not just frontend timing, so an existing account can't retroactively
  claim a referral for a friend by calling the endpoint directly.
- **Referral cap**: spec explicitly describes uncapped stacking (12+1, 13+1...).
  **Resolved in Session 4**: capped at 12 bonus months per referrer per rolling year
  (`ReferralService.ANNUAL_REWARD_CAP`). Referrals past the cap are still recorded
  (so the referrer's "friends referred" count stays accurate) but grant no bonus.
- **Feedback attachment type**: item 1 says "5MB file size max" (no type restriction),
  item 2 says "screenshots only". Assumption: Feedback = same broad allow-list as
  Document Vault (image/pdf/docx/etc, ≤5MB); Bug report = images only, ≤5MB; Idea = text
  only, no attachment (nothing in the spec suggests ideas need a file).

## Shared design (features 1, 2, 3, 5)

**One entity, not three** — `UserSubmission` (mirrors how `ExpenseShare.splitType` /
`Document.category` reuse one table with a type discriminator elsewhere in this codebase):
- `model/SubmissionType.java` — enum `FEEDBACK | BUG_REPORT | IDEA`
- `model/SubmissionStatus.java` — enum `NEW | REVIEWED | RESOLVED`
- `model/UserSubmission.java` → table `user_submissions`: `id`, `userId` (loose Long,
  same cross-feature-FK convention as `OrgOrder.receiptId`), `type`, `message` (TEXT),
  `attachmentPath` (nullable, UUID filename same pattern as `DocumentService`),
  `attachmentMimeType` (nullable), `clientLogJson` (TEXT, nullable — the auto-captured
  diagnostic bundle), `status` (default `NEW`), `rewardGranted` (boolean, only
  meaningful for `IDEA`), `createdAt` (`@PrePersist`)
- `repository/UserSubmissionRepository.java` — `findAllByOrderByCreatedAtDesc()`,
  `findByType(...)`, `countByUserIdAndType(userId, IDEA)` (for the one-reward-per-user rule)
- `service/UserSubmissionService.java` — `create()`, `listForPlatform()`, `updateStatus()`,
  `streamAttachment()`
- `controller/UserSubmissionController.java` — `POST /api/feedback` (multipart: `type`,
  `message`, optional `file`, optional `clientLog` JSON string)

**File validation** — no existing 5MB ceiling in the codebase (global Spring cap is
25MB, see `application.properties`); add a manual `file.getSize() > 5_000_000` check,
copying the `ALLOWED_MIME_TYPES`/`ALLOWED_EXTENSIONS` constant-set pattern from
`DocumentService.java` (image-only subset for `BUG_REPORT`, broader set for `FEEDBACK`).
Store under `{storagePathResolver.asPath()}/submissions/{userId}/` as a UUID filename —
never trust user-supplied filenames on disk (same rule as Document Vault / Vehicle photos).

**Email** — `EmailService.send()` (`service/EmailService.java` line ~230) currently
constructs `new MimeMessageHelper(msg, false, "UTF-8")` — `false` disables multipart,
so attachments can't be added today. Needed change: a new overload/method
`sendSubmissionNotification(UserSubmission, ownerEmail)` that builds the helper with
`true` (multipart) and calls `helper.addAttachment(filename, new ByteArrayResource(bytes))`
when an attachment exists, plus embeds the `clientLogJson` as plain text in the body.
Recipient: new config value (`PLATFORM_OWNER_EMAIL` in `local.env`/`application.properties`,
same pattern as other env-driven addresses) — not the submitting user's own email.
Keep the existing non-fatal pattern (`catch (MailException | MessagingException e) → log.warn`)
— a failed notification email must never block the submission itself from saving.

**Frontend** — new shell component (eagerly loaded in `AppModule`, not a lazy feature
module — same tier as `PastDueBannerComponent`/`WelcomeBannerComponent`):
`FeedbackWidgetComponent`, rendered in `app.component.html` next to
`<app-past-due-banner>`, gated the same way (`*ngIf="currentUser"`, excluded on
`isLoginPage` and public token routes). Contains the FAB + a modal with a type selector
(Feedback / Report a Bug / Submit an Idea) and conditionally shows the file picker
(image-only `accept` for Bug Report) or hides it entirely (Idea).
Diagnostic log capture: small `DiagnosticLogService` that ring-buffers the last ~20
`console.error`/`console.warn` calls (override `console.error` once at bootstrap) and
the last ~5 failed HTTP responses (status + URL only, via an `HttpInterceptor` already
likely present for auth — check `AuthInterceptor` before adding a new one) — serialized
to JSON and attached to the submission request, not shown to the user.

## Feature 3 — Reward (design) — **revised 2026-07-04, see Session 3 note**

Originally scoped as an automatic reward on IDEA submission. **Superseded**: reward
granting is now a manual platform-admin action taken after reviewing any submission
(feedback/bug/idea), not automatic on submission. See Session 3 checklist below for
the decisions and what was actually built.

Reuses the "grant bonus subscription time" primitive needed by feature 4 (referrals) —
build this primitive once, in Session 3, and feature 4 calls the same method in Session 4.

**Critical design point from researching the existing subscription system**: bonus
months must **not** be implemented by writing to the `Subscription` entity
(`model/Subscription.java`) the way Square-driven billing does. That entity is the
`SubscriptionReconciliationScheduler`'s source of truth for what to re-verify against
Square's API daily — a bonus-granted row with no real `squareSubscriptionId` would
either be silently skipped or cause a reconciliation lookup against a Square
subscription ID that doesn't exist. **Bonus rewards should extend the entitlement layer
directly** (`UserFeatureGrant`/`OrgFeature.expiresAt`, per `[[project-feature-entitlements]]`
memory) — the same primitive `PlatformService.grantFeature()` already uses for manual
platform-console grants. This keeps "you have access to feature X until date Y"
(entitlement) cleanly separate from "you are a paying Square customer" (billing), which
is exactly the separation the subscription-billing TODO already established on purpose.

- New method: `UserFeatureService.grantBonusMonths(Long userId, AppFeature feature, int months, String reason)`
  — if an active (non-expired) grant exists, extend `expiresAt` by N months from its
  current value (this is what produces the 12+1=13, 13+1=14 stacking behavior); if none
  exists, create one with `expiresAt = now + N months`. Log `reason` (`"idea_submission"`
  / `"referral"`) for auditability — no existing audit table covers this outside the
  immigration module, so a plain log line is enough; don't build a new audit table for this.
- **Open question tied to the "which feature" ambiguity**: the spec says "1 month free
  subscription" without saying which feature/plan. If the product only ever sells one
  bundled "Suite" plan, this is moot — grant whichever `AppFeature`(s) the Suite covers.
  If there are multiple purchasable plans (Garage/Vault/Jobs/Suite per
  `PlatformSquareConfig`'s 4 plan-ID fields), decide whether the reward grants a specific
  feature, or all of them, or requires the user to already be a Suite subscriber before
  the reward applies. Needs a product decision, not a code guess.
- `UserSubmissionService.create()`: for `type=IDEA`, call `countByUserIdAndType(userId, IDEA)`
  — if `0` (first idea ever, per the one-reward-per-user default above), call
  `grantBonusMonths(...)` and set `rewardGranted=true` on the row.

## Feature 4 — Referral program (design) — ✅ built Session 4, see session note for what actually shipped vs. this original design

- `model/User.java` — add `referralCode` (short alphanumeric, unique, `@PrePersist`
  generated). Every other invite token in this codebase uses a full UUID
  (`ExpenseShare.inviteToken`, `DocumentShare.shareToken`, etc.) — a referral code should
  be short/shareable instead, so this is new generation logic, not a copy-paste of the
  UUID convention. Simple approach: base36 of a random long, truncated/padded to 8 chars,
  retry on collision (unique index will catch it).
- New entity `model/Referral.java` → table `referrals`: `id`, `referrerUserId`,
  `referredUserId` (nullable until the invitee actually logs in), `referredEmail`
  (nullable — unknown until signup, since the code alone doesn't carry an email),
  `status` (`PENDING | COMPLETED`), `rewardGranted` (boolean), `createdAt`, `completedAt`.
- **Capture flow**: referral code travels as a query param (`?ref={code}`) on whatever
  URL the referrer shares — likely the login page or a marketing landing page. Store it
  client-side (sessionStorage, since it must survive the Google OAuth redirect round-trip)
  before the OAuth redirect fires. After login, if this is the user's *first* login
  (no existing `User` row before this OAuth callback — check `CustomOAuth2UserService`'s
  create-vs-lookup branch), read the stored code and call a new
  `POST /api/referrals/claim { code }` endpoint once.
- Backend: `service/ReferralService.claim(String code, Long newUserId)` — looks up `User`
  by `referralCode`, rejects if the caller is trying to claim their own code, rejects if
  this `newUserId` already has a completed `Referral` row as a referred party (one
  referral credit per new user, so an account can't be "referred" twice), creates/updates
  the `Referral` row to `COMPLETED`, calls `grantBonusMonths(referrerUserId, ..., 1, "referral")`.
  Per the spec, only the **referrer** gets the reward — nothing says the new user gets
  anything, so don't invent a signup bonus for them.
- Frontend: referral code + shareable link (`{FRONTEND_URL}/login?ref={code}`) surfaced
  somewhere in account/profile settings, with a copy-to-clipboard button and a simple
  count of successful referrals (`GET /api/referrals/mine`).

## Feature 5 — Platform owner console (design)

- `PlatformComponent` (`components/platform/platform.component.ts`) already has an
  `activeTab: 'orgs' | 'users'` switcher (line ~16) — widen to
  `'orgs' | 'users' | 'feedback'`, same lazy-load-on-first-click pattern as the existing
  `users` tab.
- Backend, gated the same way every other platform route is
  (`PlatformService.requirePlatformAdmin()` as the first line, `User.platformAdmin` flag):
  - `GET /api/platform/feedback?type=&status=` — list, newest first
  - `GET /api/platform/feedback/{id}/attachment` — stream file, same ownership-check
    shape as `DocumentController`'s download endpoint (here: any platform admin, not
    the submitter)
  - `PUT /api/platform/feedback/{id}/status` — mark `REVIEWED`/`RESOLVED`
- `PlatformService`/`PlatformController` — add these alongside the existing
  org/user methods rather than a new controller, to keep the single
  `requirePlatformAdmin()` gate pattern consistent (matches how `PlatformUserController`
  was added as a sibling rather than folded into `PlatformController`, so either a new
  sibling `PlatformFeedbackController` or adding to the existing one is fine — pick
  whichever keeps `PlatformController` from getting too large).
- Frontend: submitter name + email + type + status + message + attachment thumbnail
  (image types) or a generic file-download link (non-image), same as Document Vault's
  list view pattern.

## Session checklist

### Session 1 — Shared data model + attachment infra ✅ done
- [x] `SubmissionType`, `SubmissionStatus` enums
- [x] `UserSubmission` entity + repository — **deviation from plan**: `userId` implemented
      as a real `@ManyToOne User user` FK (not a loose `Long`). The loose-Long convention in
      this codebase (`OrgOrder.receiptId`, `JobApplication.resumeDocumentId`) is reserved for
      FKs that cross *feature-phase* boundaries per the app-splitting rule; `User` itself isn't
      one of those boundaries — every other feature entity (`Document.user`, `Vehicle.user`,
      `JobApplication.user`, etc.) links to `User` with a real FK, so `UserSubmission` matches
      that instead.
- [x] File validation (5MB manual check + MIME allow-lists, image-only variant for bug reports)
      — implemented in `UserSubmissionService`; IDEA type rejects any attachment outright
- [x] `EmailService`: multipart support (conditional on attachment presence, not unconditional)
      + new `sendSubmissionNotification()` method; `PLATFORM_OWNER_EMAIL` config value added to
      `application.properties` + `local.env`
- [x] `UserSubmissionService.create()` (no reward logic yet) + `UserSubmissionController`
      (`POST /api/feedback`)

Manually verified end-to-end against the running local backend: text-only feedback, bug report
with image attachment, idea with/without attachment (attachment correctly rejected), oversized
file rejection, wrong-MIME-type rejection for bug reports, missing-message rejection, attachment
persisted to `uploads/submissions/{userId}/{uuid}.ext`, and the multipart email path (submission
save is never blocked by email failure — confirmed by triggering a real SMTP auth failure against
the current `local.env` Gmail app password, which logged WARN and did not affect the saved row).

### Session 2 — Feedback + Bug Report frontend ✅ done
- [x] `DiagnosticLogService` (`services/diagnostic-log.service.ts`) — hooks `console.error`
      **and** `console.warn` once (static guard), ring-buffers last 20 entries (500-char cap
      per message) + last 5 failed HTTP calls; query strings stripped from captured URLs so
      tokens never enter the bundle; `capture()` adds route + userAgent + timestamp.
      Buffers are in-memory only — a full page reload clears them (acceptable: a user
      reporting a broken page submits from that page).
- [x] `DiagnosticLogInterceptor` (`interceptors/diagnostic-log.interceptor.ts`) — new
      interceptor registered in `AppModule` after `CredentialsInterceptor` (the "check
      AuthInterceptor first" note in the design: only CredentialsInterceptor existed, and
      it's single-purpose, so a sibling interceptor was cleaner than folding in).
- [x] `FeedbackWidgetComponent` (`components/feedback-widget/`, declared in `AppModule`
      shell tier) — FAB (fixed bottom-right, safe-area aware, z-index 1030 under the
      welcome-banner backdrop) + modal with 3-way type selector; file picker hidden for
      IDEA, image-only `accept` for BUG_REPORT; client-side 5MB + image-type pre-checks
      mirror the backend rules; backend error messages surfaced verbatim (not swallowed).
- [x] `FeedbackService` (`services/feedback.service.ts`) — multipart POST `/api/feedback`.
- [x] Wired into `app.component.html` inside the `!isLoginPage` shell, `*ngIf="currentUser"`
      — absent on login and all public token routes (verified on `/share/{token}`).

Verified end-to-end with Playwright against the running local stack (backend `local`
profile + `ng serve`): all 3 types submitted and persisted (H2 rows inspected directly);
oversize (6MB) and non-image-on-bug-report rejected client-side with inline errors;
IDEA hides the picker; Send disabled on empty message; form resets after success;
`client_log_json` in the DB confirmed to contain injected console errors AND a real failed
HttpClient call (`GET /api/receipts/999999` → status 500, captured via the interceptor);
email failure stayed non-fatal (WARN, row still saved). Test rows/attachments cleaned up.

### Session 3 — Bonus-months primitive + admin-granted reward ✅ done

**Design change made this session** (user-directed, supersedes the original Feature 3
design and the TODO's own open question about automatic-vs-approved): reward granting
is **not** automatic on IDEA submission. Instead, the platform admin reviews any
submission (feedback/bug/idea) in the console and manually grants a 1-month bonus for a
feature of their choosing. This sidesteps the whole "how do we prevent farming an
automatic reward" concern from the original open-decisions section — a human is gating
every grant, so no rate-limit/one-time-per-user rule is needed.

Sub-decisions confirmed with the user:
- Which feature(s) does the bonus month apply to? → admin picks a single `AppFeature`
  explicitly per grant (not auto-derived from "Suite"); works for any of the 5
  `AppFeature` values, whatever the admin decides fits the submission.
- Extend-existing vs extend-or-create? → **extend-or-create**. Since a human is making
  the call (not an automated/farmable flow), the admin can create a fresh 1-month grant
  even for a feature the user isn't currently subscribed to, not just extend one they
  already have.
- Perpetual grants (`expiresAt == null`, meaning an active paid subscription driven by
  `grantFeature()`) are a no-op — there's nothing finite to extend, and setting a finite
  `expiresAt` on top would shorten their access rather than add to it. Logged, not
  treated as an error.

- [x] `UserFeatureService.grantBonusMonths(Long userId, AppFeature feature, int months, String reason)`
      — entitlement-layer extension, NOT the `Subscription` entity (see design note
      above); extend-or-create semantics; returns the resulting `expiresAt`, or `null` if
      skipped because the grant is already perpetual. Reusable as-is by Session 4
      (referrals) — no changes needed there.
- [x] `PlatformFeedbackController` (`/api/platform/feedback`) — new sibling controller,
      same pattern as `PlatformUserController` (thin controller, direct repo + service
      calls, `platformService.requirePlatformAdmin()` gate, no dedicated service layer
      for this one action). `PUT /{id}/grant-reward` — body `{ feature, months? }`
      (months defaults to 1); rejects if the submission already has `rewardGranted=true`
      (submission-level idempotency — admin can't double-grant off the same review by
      clicking twice); calls `grantBonusMonths(submission.user.id, feature, months,
      "admin_review:" + submission.type)`; sets `rewardGranted=true` on the submission.
- [x] Removed `UserSubmissionRepository.countByUserAndType()` — was added in Session 1
      anticipating the automatic one-reward-per-user rule; unused now that granting is
      manual, so deleted rather than left as dead code.
- [x] Updated `UserSubmission.rewardGranted` javadoc — was "only meaningful for
      type=IDEA", now applies to any submission type since any reviewed submission can
      earn a manual grant.
- 10 new tests: `UserFeatureServiceTest` (4 — fresh grant, stacking extend, expired
  reactivation, perpetual no-op) + `PlatformFeedbackControllerTest` (6 — grant success,
  default/explicit months, double-grant rejection, unknown feature, missing submission,
  non-admin). Full suite: 210 tests, same 5 pre-existing unrelated
  `FeatureEntitlementServiceTest` failures (confirmed pre-existing by running that class
  alone before touching anything else this session — not caused by this work).

**Not built this session** (still Session 5's job): `GET /api/platform/feedback` list
endpoint, attachment streaming, status update endpoint, and all frontend (console tab,
grant-reward button, list/detail UI). Session 3 only needed the primitive + the action
that fires it to exist as a backend capability; there was nothing yet to list against
since Session 5 owns the listing endpoint.

### Session 4 — Referral program ✅ done

**Design decisions confirmed with the user before implementing** (both flagged as real
risks in the open-decisions section above — referral rewards are unsupervised, unlike
Session 3's admin-reviewed submission rewards, so the abuse questions matter more here):
- **Annual cap**: 12 bonus months per referrer per rolling year, not uncapped as the
  literal spec describes. Referrals past the cap are still recorded (accurate "friends
  referred" count) but grant no reward.
- **Reward scope**: fixed feature (`AppFeature.EXPENSE_SHARING`), extend-or-create
  semantics — unlike Session 3's admin-picks-per-grant, this flow is automatic so the
  feature has to be a fixed code decision, not a per-grant choice.

**First-login enforcement — new mechanism, not in the original design**: the original
plan said "check `CustomOAuth2UserService`'s create-vs-lookup branch" but didn't specify
*how* that reaches the claim endpoint later in a different request. Built a one-shot
HttpSession flag: `CustomOAuth2UserService.loadUser()` enriches the returned `OAuth2User`
with an `isNewSignup` attribute (wraps it in a new `DefaultOAuth2User` — attributes map
is otherwise immutable); `OAuth2SuccessHandler` reads that attribute and sets
`NewSignupFlag.SESSION_KEY` on the session; `GET /api/auth/me` surfaces it read-only as
`isNewUser` (doesn't clear it — safe to call on every page load/refresh); `POST
/api/referrals/claim` is the only consumer that clears it, and rejects outright if it's
absent. This closes the collusion gap where an existing account could otherwise call the
claim endpoint directly (bypassing the frontend's timing) to retroactively credit a
referral for a friend — the session flag ties "one claim opportunity" to "the exact
session created by a genuine first-ever OAuth login," not just "an account not yet
credited."

- [x] `User.referralCode` (nullable unique column) — generation is lazy (on first
      `GET /api/referrals/mine` or claim, not `@PrePersist` — entities can't query the DB
      for collision-retry, so generation lives in `ReferralService.ensureReferralCode()`
      instead, retrying on `DataIntegrityViolationException`)
- [x] `Referral` entity + repository — **deviation from plan**: dropped the planned
      `PENDING` status and separate `referredEmail` column. Rows are only ever created at
      claim time (login has already happened, so `referredUser` is always known
      immediately) — there's no earlier event to persist a `PENDING` row against, and
      `referredEmail` is redundant with `referredUser.email`. Unique constraint on
      `referred_user_id` enforces one referral credit per new user at the DB level.
- [x] Signup-time capture: `LoginComponent` stores `?ref=` in `sessionStorage`
      (survives the Google OAuth redirect); `AppComponent`'s existing first-user-emission
      subscription (same block that handles `postLoginRedirect`) calls
      `ReferralService.claimPendingCodeIfAny(user.isNewUser)` once
- [x] `ReferralService.claim()` → `grantBonusMonths(referrer.id, EXPENSE_SHARING, 1, "referral")`;
      rejects self-referral, double-claim (via the unique constraint check), and
      unknown codes; skips (but still records) the reward once the annual cap is hit
- [x] `GET /api/referrals/mine` + UI — added as a "Refer a Friend" card in
      `StorageSettingsComponent` (`/settings`) rather than a new route/page — it's the
      only existing account-settings-shaped page in the app; code + link with
      copy-to-clipboard, friends-referred count, and rewarded-this-year/cap progress

12 new tests: `ReferralServiceTest` (8 — claim success, self-referral, double-claim,
unknown code, annual cap, lazy code generation x2, summary DTO) + `ReferralControllerTest`
(5 — claim success, rejected without flag, rejected when flag false, service error
propagation, mine passthrough). Full suite: 223 tests, same 5 pre-existing unrelated
`FeatureEntitlementServiceTest` failures. Frontend: `ng build` clean.

**Not manually end-to-end tested**: the real OAuth login → referral claim flow can't be
exercised in local dev — `LocalDevSecurityFilter` bypasses `CustomOAuth2UserService`
entirely (same limitation as the email-match testing workarounds in
`CLAUDE.local.md`), so `isNewUser` can never be true locally without directly patching
session state. Verified via unit tests instead. Also found (and left alone) a
backend process already running on port 8080 from outside this session — didn't
restart it to avoid disrupting whatever it's being used for.

### Session 5 — Platform owner console ✅ done

- [x] `PlatformSubmissionDTO` (`dto/`) — admin-facing view of `UserSubmission`; adds
      `submitterName`/`submitterEmail` (resolved from `UserSubmission.user`) that the
      self-service `UserSubmissionDTO` intentionally omits (that one is returned to the
      submitter about their own submission — no need to echo their own name/email back).
- [x] `UserSubmissionService` additions — `listForPlatform(type, status)` (both filters
      optional; type filters via a repo query, status filters in-memory since there's no
      `findByStatus` repo method and the list is expected to stay small), `updateStatus(id,
      status)`, `streamAttachment(id)` (returns a `Resource`, mirrors
      `DocumentService.download()`). Authorization is NOT checked here — same convention
      Session 3 established: `platformService.requirePlatformAdmin()` gates in the
      controller, this service only does data access. Also deduped `create()`'s inline
      type-parsing into the same `parseType()` helper these new methods use.
- [x] `PlatformFeedbackController` additions (sibling to the Session 3 grant-reward
      endpoint, same controller): `GET /api/platform/feedback?type=&status=`,
      `PUT /api/platform/feedback/{id}/status`, `GET /api/platform/feedback/{id}/attachment`
      (streams via `submissionService.streamAttachment()`; `Content-Disposition: inline`
      rather than `attachment` — unlike `DocumentController`'s always-`attachment` pattern —
      so images can render directly in an `<img>` tag for the thumbnail requirement; the
      filename in the header is the server-generated UUID name, not sensitive, so no
      sanitization needed unlike `DocumentController`'s user-supplied-filename case)
- [x] `PlatformComponent` — `activeTab` widened to include `'feedback'`; new tab loads
      lazily on first click (matches the existing `users` tab pattern); type/status filter
      dropdowns re-fetch on change; row click expands to show full message, status buttons
      (Mark Reviewed / Mark Resolved), and — only when `!rewardGranted` — a grant-reward
      form (feature dropdown from the existing `userFeatures` list + months input) wired to
      the Session 3 endpoint. Image attachments render as a 44px thumbnail via
      `<img [src]="attachmentUrl(s)">`; non-image attachments get a "View" link that opens
      the same URL in a new tab — both just point at the plain URL string (same pattern as
      `DocumentService.downloadUrl()`: session cookies ride along automatically since
      local dev is same-site even across the :4200/:8080 ports, and prod is same-origin
      through nginx, so no explicit credentials wiring was needed).
- 23 new backend tests (`UserSubmissionServiceTest` — 9, listForPlatform submitter
  identity/type-filter/status-filter, updateStatus, invalid status, streamAttachment
  found/no-attachment/missing-from-disk, unknown-id; `PlatformFeedbackControllerTest` — 14
  total including the Session 3 ones, +9 new for list/updateStatus/attachment). Full
  suite: 239 tests, same 5 pre-existing unrelated `FeatureEntitlementServiceTest`
  failures. `ng build` clean.

**Manually verified end-to-end in a real browser** (user asked for this explicitly after
two prior sessions deferred it due to an unrelated process already on port 8080 — user
approved stopping it and restarting fresh; Playwright + Chromium driven via a throwaway
script, not a project skill since none existed for this repo): submitted a FEEDBACK item
and a BUG_REPORT with a real image attachment through the FAB widget; flipped
`dev@localhost.local` to `platformAdmin=true` via the H2 console (same manual workaround
`CLAUDE.local.md` documents for other local-only gates); opened `/platform` → Feedback
tab — list rendered submitter name/email/type/message/attachment thumbnail/status/reward
columns correctly, including older rows from prior sessions' manual testing (confirms
real DB persistence across sessions, not a fixture); expanded a row, clicked "Mark
Reviewed" (status pill flipped to REVIEWED), then granted a bonus-month reward (feature
dropdown defaulted to Expense Sharing, 1 month) — UI flipped to "Reward already granted
for this submission," and `curl`/server log confirmed it was real: a `user_feature_grants`
row was inserted with `expires_at = now + 1 month`, not just client-side state. Two
pre-existing rows from earlier sessions (ids 2, 33) logged `File not found in storage`
when the console tried to load their thumbnails — expected, not a bug: Session 2's notes
say test attachments were manually cleaned up from disk after that session, but the DB
rows were left in place; the endpoint degrades gracefully (broken image icon, not a
crash). No new console errors — the only browser console warnings were pre-existing and
unrelated (New Relic config 404 in local dev).

### Session 6 — Hardening / manual QA
- [ ] Abuse-mitigation follow-ups from the open-decisions section (rate limits, caps,
      alerting on unusual referral counts) — depends on what the user decides up front
- [ ] Manual test: submit each of the 3 types with/without attachment, confirm email
      arrives with attachment + log; confirm idea reward applies once only; confirm
      referral stacking math (12+1=13, 13+1=14) end-to-end with two test accounts
- [ ] Confirm bonus-granted entitlements don't get swept up by
      `SubscriptionLockoutScheduler`/`SubscriptionReconciliationScheduler` (they
      shouldn't, since bonus rewards never touch the `Subscription` table — verify
      this holds once built)

## Don't
- Don't write bonus/reward months to the `Subscription` entity — it's Square's billing
  source of truth and is polled/reconciled against Square's API; bonus grants belong on
  `UserFeatureGrant`/`OrgFeature.expiresAt` instead (see Feature 3 design note)
- Don't build 3 separate tables for feedback/bug/idea — one `UserSubmission` with a
  `type` discriminator, matching the `ExpenseShare.splitType` / `Document.category`
  convention already used elsewhere in this codebase
- Don't skip the 5MB manual size check on the assumption Spring's global 25MB
  `max-file-size` covers it — that ceiling is for the whole app, not this feature
- Don't grant the idea/referral reward without some abuse guard (rate limit / one-time
  rule) — the spec as given has no cap and is otherwise trivially farmable
- Don't invent a new audit table for reward grants — a log line is sufficient; this
  isn't the immigration module's ReBAC/compliance surface
