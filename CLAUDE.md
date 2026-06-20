# Backend — Spring Boot 3.2 / Java 17

## Package layout
```
com.receipttracker
├── config/       CorsConfig
├── controller/   HTTP layer only — validates input, delegates to service
├── dto/          Request/response shapes (manually mapped, no MapStruct)
├── model/        JPA entities
├── repository/   Spring Data JPA interfaces
├── security/     SecurityConfig, CustomOAuth2UserService, OAuth2SuccessHandler
└── service/      All business logic
```

## Controller conventions
- `@RestController @RequestMapping("/api/...")` — no base class
- Log entry with `log.info/trace`, capture `startTime = System.currentTimeMillis()`
- Delegate entirely to service; catch exceptions → `ResponseEntity.badRequest().body(Map.of("error", msg))`
- Don't put business logic in controllers

## Service conventions
- All mutating methods: `@Transactional`
- All read-only methods: `@Transactional(readOnly=true)`
- User resolution: always call `currentUser()` helper → SecurityContextHolder → googleId → DB
- Entity↔DTO mapping: manual `toDTO()` / `fromDTO()` methods inside the service
- Throw `RuntimeException` for domain errors; controller catches them
- Log pattern: `>>>` entry, `<<<` success exit, `!!!` error; `TRANSACTION: X START/COMMITTED/ROLLED_BACK`

## Entities (JPA)
- All use `@Data @NoArgsConstructor @AllArgsConstructor` (Lombok)
- Monetary fields: always `BigDecimal`, never `double`/`float`
- `Receipt.items` uses `CascadeType.ALL` + `orphanRemoval=true` — on update, clear the list and re-add
- `paymentCard` format: `{CARDBANK}_{CARDTYPE}_{LASTFOUR}` (e.g. `CHASE_VISA_1234`)
- `Receipt.vehicle` — nullable `@ManyToOne Vehicle`; set via `PUT /api/receipts/{id}/vehicle`; body: `{ vehicleId: Long|null, vehicleCategory: String|null }`; `ReceiptDTO` exposes `vehicleId`, `vehicleName` (computed: `"{year} {make} {model}"`), and `vehicleCategory`
- `Receipt.vehicleCategory` — nullable String; values: `FUEL`, `MAINTENANCE`, `REPAIR`, `INSURANCE`, `REGISTRATION`, `PARKING`, `WASH`, `OTHER`; stored uppercase; cleared to null when vehicle is unlinked
- AWS credentials in `User` are AES-256-GCM encrypted — always go through `EncryptionService`
- `@PrePersist` sets `createdAt`/`uploadedAt` — don't set manually

## Receipt upload pipeline
```
POST /api/receipts/upload
  → OcrService.processUpload()           saves file, converts HEIC→JPEG, runs Tesseract
  → ClaudeVisionService.analyze()        primary parser (optional)
  → ReceiptParserService.parse()         regex fallback
  → Receipt + ReceiptItem persist
  → userStorageService.finalizeStorage() move file to S3/custom path (non-fatal)
```
Vision AI failures are always non-fatal — always fall back to regex parser silently.

HEIC/HEIF conversion (iPhone camera photos): tries `sips` (macOS), then `heif-convert`
(Linux/Docker), then `convert` (ImageMagick). Each converter is given a 60 s timeout
with `p.waitFor(60, TimeUnit.SECONDS)` — the process is force-killed on timeout.
Spring and nginx both accept up to 25 MB uploads; nginx upload location has 300 s timeouts.

Tessdata path fix: Ubuntu installs trained data to `/usr/share/tesseract-ocr/4.00/tessdata/`.
The Dockerfile creates a symlink `/usr/share/tessdata → /usr/share/tesseract-ocr/4.00/tessdata/`
so the app's configured `TESSDATA_PATH=/usr/share/tessdata` resolves correctly.
`OcrService.extractFromImage()` guards with `new File(tessDataPath, "eng.traineddata").exists()`
before calling `tess.doOCR()` — missing tessdata causes a native SIGSEGV that kills the JVM.

## Receipt parsing (ReceiptParserService)
- ALDI receipts: item lines start with a 6-digit code (`356567 Organic Red Grapes  4.99 FA`);
  matched by `ALDI_ITEM_PATTERN`. Trailing `FA`/`FB` tax flags are ignored.
- ALDI qty breakdown lines (`3 x  0.55`) are parsed separately to back-fill the previous
  item's `quantity` and `unitPrice`; the total price is already on the item line.
- "T O T A L" (letters spaced out) — ALDI prints the total word this way. Handled by
  compacting whitespace (`u.replaceAll("\\s+","")`) before the `startsWith("TOTAL")` check.
- Generic item pattern requires 2+ spaces before the price to avoid false positives.
- Vision AI (`ClaudeVisionService`) is the primary parser when `VISION_AI_ENABLED=true`;
  far more accurate than regex for unusual layouts — enable with `ANTHROPIC_API_KEY` in env.

## Expense sharing
- Entity: `ExpenseShare` → table `expense_shares`; status enum: `ShareStatus`
  (`PENDING`, `ACCEPTED`, `DENIED`, `CHANGE_REQUESTED`, `CHANGE_APPROVED`, `CHANGE_REJECTED`)
- `inviteToken` is a UUID generated in `@PrePersist`; never logged verbatim
- `splitType` field on `ExpenseShare`: `EQUAL`, `CUSTOM`, `ITEM_BASED`, or `PAID_FOR_ME`
- Equal split divides by `invitees.size() + 1` — the +1 accounts for the owner's share
- **PAID_FOR_ME split**: single invitee is the payer (creditor); `paidForOwner=true` on the share; the owner owes the invitee, not the other way around; share response page shows "Debt Confirmation" to the payer
- **ITEM_BASED split**: `CreateShareRequest.itemAssignments` maps each invitee email → list of
  `ReceiptItem` ids. All ids must belong to the receipt or a `RuntimeException` is thrown.
  Tax: `effectiveTaxRate = receipt.tax / receipt.subtotal` (0 if subtotal is null/0).
  For each taxable item: `taxAmount = item.totalPrice * effectiveTaxRate`.
  Share total = `Σ(itemTotalPrice) + Σ(taxAmount for taxable items)`, rounded HALF_UP to 2 dp.
  Item rows stored in `ExpenseShareItem` (table `expense_share_items`).
- Entity: `ExpenseShareItem` → `(id, share_id FK, receipt_item_id FK, itemTotal, taxAmount, taxRate)`
- `ExpenseShareItemRepository.findByShare(share)` used in `getShareByToken` to populate
  `ShareViewDTO.assignedItems`, `itemSubtotal`, `itemTax` for ITEM_BASED shares
- Receipt ↔ Group link: `Receipt.group` is a nullable `@ManyToOne ExpenseGroup`.
  `ReceiptService.addToGroup(receiptId, groupId)` validates receipt ownership + group membership;
  `groupId == null` unassigns. Exposed via `PUT /api/receipts/{id}/group`.
- Public endpoint `GET /api/shares/token/**` is `permitAll()` for GET only — POST requires auth
  (enforced with `requestMatchers(HttpMethod.GET, "/api/shares/token/**")` in SecurityConfig)
- Email notifications: invite → invitee; change-request → owner; approve/reject → invitee;
  **accept/deny → owner** (owner gets notified whenever invitee takes any action)
- Email: `EmailService` uses `@Autowired(required=false) JavaMailSender` — if no SMTP config,
  falls back to `log.warn`; email failures are never propagated (non-fatal)
- Requires `GMAIL_USERNAME` + `GMAIL_APP_PASSWORD` env vars for real email delivery

## Document Vault
- Entities: `Document` → `documents`; `DocumentNextStep` → `document_next_steps`; `DocumentShare` → `document_shares`; join table `document_share_docs`
- `DocumentCategory` enum: RESUME, TAX, INCOME, IMMIGRATION, OTHER
- Status is computed in `DocumentService.toDTO()` — not stored: `expiryDate == null → ACTIVE`; within 90 days → `EXPIRING_SOON`; past → `EXPIRED`
- Files stored as UUID filenames under `{storagePathResolver.asPath()}/documents/{userId}/` — never expose user-supplied filenames in the file system path
- Allowed MIME types enforced in `DocumentService.ALLOWED_MIME_TYPES` + `ALLOWED_EXTENSIONS` — both must pass or upload throws
- `DocumentShare.shareToken` is UUID generated in `@PrePersist`; never logged
- Share expiry is checked in `getByToken()` and `downloadViaToken()` — throws if past `expiresAt`
- Download endpoint (`GET /api/documents/{id}/download`) verifies ownership; download via token verifies document is in the share's document list
- `DocumentController` sanitizes `Content-Disposition` filename (strips non-ASCII characters)
- `GET /api/documents/shared/**` is `permitAll()` — scoped to GET only in `SecurityConfig` (same pattern as expense shares)
- `DocumentService.getSummary()` is called on every dashboard load — keep it lightweight (one DB query + in-memory grouping)
- `EmailService.sendDocumentShare()` follows the same non-fatal pattern as other email methods

## Garage / Vehicle Maintenance Tracking
- Entities: `Vehicle`, `MaintenanceRecord`, `FuelRecord`, `VehicleAccess` + enums `MaintenanceType`, `FuelType`, `VehicleAccess.AccessStatus`
- Tables: `vehicles`, `maintenance_records`, `fuel_records`, `vehicle_access`
- `VehicleAccess` — `(id, vehicle_id FK, user_id FK nullable, invitee_email, status PENDING/ACCEPTED/REVOKED, invite_token UUID, granted_at)`; unique on `(vehicle_id, invitee_email)`; `user_id` is null until the invitee accepts
- Access control in `VehicleService`: `requireOwned(id)` for owner-only ops (delete vehicle, manage sharing, update metadata, add/remove photos); `requireCanView(id)` for everything else (view, add maintenance, add fuel, etc.)
- `listMine()` returns owned vehicles + ACCEPTED shared vehicles; shared entries have `isShared=true`, `ownerName` set
- `VehicleDTO` has `isShared`, `ownerName`, `sharedWith: List<VehicleAccessDTO>` (only populated on `getById` for owners)
- `Vehicle.photoFilenames` is an `@ElementCollection` stored in a join table `vehicle_photos` (one row per photo)
- `MaintenanceRecord.linkedReceipt` is a nullable FK to `Receipt` — users can link fuel/maintenance to scanned receipts
- `FuelRecord` stores fill-up details; MPG is computed in `VehicleService` from consecutive full-tank fills
- `MaintenanceScheduleService` provides hardcoded OEM-style intervals (oil change 7.5K mi, tire rotation 7.5K mi, etc.) — no external API; intervals are published by manufacturers
- Schedule status computed: overdue if current mileage ≥ due mileage OR current date ≥ due date; due-soon if within 500 miles or 30 days
- Next service summary: "Oil Change overdue by 200 mi" — used on vehicle card / dashboard widget
- `NhtsaApiService` proxies free NHTSA APIs (no auth):
  - `GET /api/nhtsa/makes` — all car makes, cached 24h
  - `GET /api/nhtsa/models?make={name}&year={year}` — models for a make+year, cached 12h
  - `GET /api/nhtsa/vin/{vin}?year={y}` — decodes VIN (optional year), returns make/model/trim
  - `GET /api/vehicles/{id}/recalls` — delegates to NhtsaApiService, calls NHTSA Recalls API
  - `GET /api/vehicles/{id}/receipts` — owner or accepted shared user (`requireCanView`); returns light receipt summaries (id, storeName, total, purchaseDateTime, storeType, vehicleCategory) for receipts linked via `PUT /api/receipts/{id}/vehicle`
- `VehicleReportService` generates a multi-page PDF ("Vehicle for Sale" report) using Apache PDFBox 3.0.3:
  - Page 1: vehicle specs (make/model/year/VIN/color), registration (license plate, tag expiry), insurance, purchase info, summary (total maintenance cost, avg MPG, service count)
  - Page 2: full service history table (date, service type, mileage, cost, provider)
  - Page 3: fuel log table + open recalls with campaign numbers and summaries
  - Report is non-PDF-encrypted, safe to share; endpoint: `GET /api/vehicles/{id}/report` → binary response with `Content-Disposition: attachment`
- File storage: photos stored under `{storagePathResolver.asPath()}/vehicles/{vehicleId}/` as UUID names; MIME types validated (JPEG, PNG, WebP, HEIC, PDF)
- Access control: all vehicle operations check `Vehicle.user == currentUser()`; no sharing model
- **30-day edit window**: `PUT /api/vehicles/{v}/maintenance/{r}` and `PUT /api/vehicles/{v}/fuel/{r}` check `createdAt.isBefore(now - 30 days)` and throw if expired; frontend enforces the same check client-side before showing the Edit button
- `Vehicle.modelYear` (not `year`) — `year` is a reserved keyword in H2; field renamed to avoid DDL failures

## Groups
- Entity: `ExpenseGroup` → table `expense_groups` ("groups" is a MySQL reserved word — never use it as table name)
- Entity: `GroupMember` → table `group_members`; roles: OWNER, MEMBER
- `inviteToken` UUID on `ExpenseGroup` is the QR/link token for joining; public GET permitted
- `GET /api/groups/join/:token` — public (no auth); `POST /api/groups/join/:token` — auth required
- Routes: POST /api/groups, GET /api/groups/mine, GET/POST /api/groups/join/:token,
  GET /api/groups/:id, GET /api/groups/:id/receipts (members only), DELETE /api/groups/:id (owner only)
- `DELETE /api/groups/:id`: OWNER only; unassigns all receipts (sets group=null), deletes all members, deletes group
- `GET /api/groups/:id/receipts`: returns light receipt summaries (id, storeName, total, purchaseDateTime, ownerEmail) for all group members to see

## Organization / Admin Portal
- Entities: `Organization` → `organizations`; `OrgMembership` → `org_memberships`
- `Organization`: id, name, slug (unique), plan (FREE/PRO), status (ACTIVE/SUSPENDED), owner FK → users; annotated `@DynamicUpdate` so Hibernate only includes changed columns in UPDATE — prevents Square credential columns from being overwritten when name/slug changes
- `OrgMembership`: id, org FK, user FK (nullable until accepted), inviteEmail, role (OWNER/ADMIN/STAFF/VIEWER), status (PENDING/ACTIVE/REVOKED), inviteToken UUID, invitedAt, joinedAt
- `OrgMembership` unique on `(org_id, invite_email)`; `user_id` null until invitee accepts
- `OrganizationService.create()` saves org + OWNER membership row in one transaction
- `OrganizationService.delete()` requires OWNER; deletes in order: OrgOrders → OrgMemberships → Organization; receipts are intentionally left intact (they belong to users)
- `OrganizationService.update()` uses `saveAndFlush()` + DB reload to ensure the returned DTO reflects actual DB state, especially Square fields
- Role hierarchy enforced via `requireAtLeast(org, minimum)` → `roleLevel()` int comparison (OWNER=4, ADMIN=3, STAFF=2, VIEWER=1)
- `User.platformAdmin` boolean — `Boolean.TRUE.equals(user.getPlatformAdmin())` used everywhere (null-safe for existing rows)
- `GET /api/org/join/{token}` is `permitAll()` for GET only — same pattern as vehicle/group joins; POST requires auth
- `EmailService.sendOrgInvite()` — follows same non-fatal SMTP pattern as all other email methods
- **Re-invite fix**: `invite()` calls `findByOrgAndInviteEmail` first; if a REVOKED row exists it is reused (reset token, status→PENDING, user/joinedAt cleared) rather than inserting a new row that would violate the unique constraint on `(org_id, invite_email)`

## Phase 2 — Per-org Square
- `Organization` has `squareAccessTokenEnc` (AES-256-GCM encrypted, 1024 chars), `squareApplicationId`, `squareLocationId`, `squareEnvironment` (SANDBOX/PRODUCTION); `isSquareConfigured()` checks token non-null
- `SquareApiService.SquareCreds` inner record: `accessToken`, `environment`, `applicationId`, `locationId`; `baseUrl()` returns sandbox/prod URL; all API methods have `*ForCreds(SquareCreds, ...)` variants; `envCreds()` falls back to global env vars for the original shop
- `OrgSquareController` (`/api/organizations/{slug}/square`): GET (VIEWER+), PUT (ADMIN+), DELETE (OWNER), POST `/test` (VIEWER+); raw token never returned — `OrgSquareConfigDTO` has `configured: boolean`
- `OrgOrder` entity → `org_orders`: org FK, placedBy FK, squareOrderId, squarePaymentId, totalAmount, locationId, storeName, receiptId (plain Long, no JPA relation), status (COMPLETED/REFUNDED/CANCELLED), placedAt
- `OrgOrderController` (`/api/organizations/{slug}/...`): `GET catalog`, `GET locations`, `POST payments` (saves both personal Receipt + OrgOrder), `GET orders` — all STAFF+
- `OrganizationDTO` now includes `squareConfigured`, `squareEnvironment`, `recentOrderCount` (last 50 orders)

## Phase 3 — Platform Admin
- `PlatformController` at `/api/platform`: `GET /orgs`, `GET /stats`, `PUT /orgs/{slug}/status`, `PUT /orgs/{slug}/plan` — all require `platformAdmin=true`
- `PlatformService.requirePlatformAdmin()` uses `getAttribute("sub") → findByGoogleId` pattern; throws if `!Boolean.TRUE.equals(user.getPlatformAdmin())`
- `PlatformStatsDTO`: totalOrgs, activeOrgs, suspendedOrgs, freeOrgs, proOrgs, totalMembers, squareConfiguredOrgs
- To make a user a platform admin: `UPDATE users SET platform_admin = true WHERE email = '...'` (no UI — DB-only)

## Vision AI config
- Feature-flagged: `vision.ai.enabled=${VISION_AI_ENABLED:false}`
- Set `ANTHROPIC_API_KEY` + `VISION_AI_ENABLED=true` in `local.env` to enable
- PDFs are rendered page-by-page to PNG before sending (up to `vision.ai.max-pdf-pages`, default 5)
- Model: `claude-sonnet-4-6` (override with `VISION_AI_MODEL`)

## Job Application Tracker
- Entities: `JobApplication` → `job_applications`; `InterviewRound` → `interview_rounds`
- Enums: `JobApplicationStatus`, `InterviewFormat`, `InterviewOutcome`
- `JobApplication.resumeDocumentId` nullable Long FK → `Document.id`; title resolved via `documentRepo.findById` in `toDTO()` (no JOIN) → `resumeDocumentTitle`
- `nextInterviewAt` computed in `toDTO()`: earliest future PENDING `InterviewRound.scheduledAt`
- `followUpDue` computed in `toDTO()`: `followUpDate <= today` and status not in {REJECTED, WITHDRAWN, GHOSTED, OFFER}
- `JobApplicationController` routes all under `/api/jobs`; no public endpoints — all require auth
- `InterviewRound.outcome` default `PENDING` set in both `@PrePersist` and `mapRoundFromRequest` guard
- `JobApplicationService.getSummary()` iterates all applications in-memory — same pattern as `DocumentService.getSummary()`

## Analytics
- `AnalyticsDTO` includes `spendingByCategoryPerMonth: Map<month, Map<category, amount>>` — computed in `AnalyticsService` alongside `spendingByMonth`; used by the MoM comparison chart on the dashboard
- Month key format: `"yyyy-MM"` (e.g. `"2025-06"`); category keys match `StoreType` enum names

## Cashback engine
- Rates live in `CashbackService.CARD_RATES` static map (card → category → %)
- `CARD_DISPLAY` maps internal key → human label
- Adding a new card: add to both `CARD_RATES` and `CARD_DISPLAY`
- `toDTO()` in `ReceiptService` always populates `cashbackEarned`, `potentialCashback`, `bestCard`

## Security / auth
- Google OAuth2 → Spring Security session (JSESSIONID), not JWT
- Profile `local`: all `/api/**` endpoints open (no auth gate) — safe for H2 dev
- Profile `local-mysql`: same auth bypass as `local` via `LocalDevSecurityFilter`; uses MySQL
- Profile `prod`/`test`: all `/api/**` requires authenticated session
- `LocalDevSecurityFilter` (`@Profile({"local","local-mysql"})`, `@Order(HIGHEST_PRECEDENCE)`):
  auto-injects mock OAuth2 principal (googleId=`local-dev-user`, email=`dev@localhost.local`)
  so the frontend never shows the login page during local dev
- CORS: allows `http://localhost:4200` and `${FRONTEND_URL}` with `allowCredentials=true`
- Cookie: `SameSite=None; Secure` in prod; `SameSite=Lax` locally

## Key env vars
| Env var | Default | Notes |
|---|---|---|
| `GOOGLE_CLIENT_ID` | — | required |
| `GOOGLE_CLIENT_SECRET` | — | required |
| `ANTHROPIC_API_KEY` | `""` | optional |
| `VISION_AI_ENABLED` | `false` | set `true` to use Claude |
| `ENCRYPTION_SECRET` | `change-this-…` | **must change in prod** |
| `TESSDATA_PATH` | `/opt/homebrew/share/tessdata` | macOS M1 default |
| `APP_LOG_LEVEL` | `INFO` | set `DEBUG` to trace SQL + security |
| `FRONTEND_URL` | `http://localhost:4200` | CORS + OAuth redirect |
| `DB_URL/DB_USER/DB_PASS` | MySQL on localhost | overridden by profile |
| `GMAIL_USERNAME` | `""` | Gmail address for expense-share emails |
| `GMAIL_APP_PASSWORD` | `""` | Gmail App Password (not account password) |

## Immigration module (`com.receipttracker.immigration`)

Sub-packages: `model`, `repository`, `service`, `controller`, `dto`

**Table prefix**: all `imm_` tables. Only `user_id` FK crosses feature boundaries — no references to receipts, vehicles, or documents.

**ReBAC access control** — `PermissionService` is the single gate:
- `requireAccess(user, caseId, GrantScope)` — call as the FIRST line of every service method that touches case data; no exception, no bypass
- Checks active (non-revoked) `Grant` for (user directly) OR (any org the user is an active member of)
- Scopes: `READ_CASE`, `WRITE_CASE`, `READ_FORMS`, `WRITE_FORMS`, `READ_DOCUMENTS`, `WRITE_DOCUMENTS`, `MESSAGING`

**Grant model** — `imm_grants`: exactly one of `subjectUser`/`subjectOrg` non-null (enforced in service); `relationship` enum (BENEFICIARY, ATTORNEY, PARALEGAL, HR_ADMIN, EMPLOYER_REP); `revokedAt` null means active

**`-1L` sentinel** — `activeOrgIds()` returns `List.of(-1L)` when user has no org memberships; prevents empty JPA IN clause in `existsActiveGrant()`

**Audit log** — `ImmAuditEvent` / `AuditService` is append-only: no update/delete methods on `ImmAuditEventRepository`. `AuditService.append()` is non-fatal (catches exceptions). `appendSystem()` sets actor=null.

**Activity feed** — `ActivityFeedService.getFeed()` filters events by `FeedVisibility`: ALL visible to any grantee; ATTORNEY_ONLY to ATTORNEY/PARALEGAL; BENEFICIARY_ONLY to BENEFICIARY.

**Consent** — `ConsentRecord` is permanent log; `ConsentService.revokeConsent()` side-effect: revokes all active `Grant` rows for that relationship; beneficiary-only write path.

**Messaging** — channel isolation in `MessageService.requireChannelAccess()`: SHARED=open (any grantee); ATTORNEY_BENEFICIARY=ATTORNEY/PARALEGAL/BENEFICIARY only; ATTORNEY_EMPLOYER=ATTORNEY/PARALEGAL/HR_ADMIN only.

**JSON TEXT columns** — `@Column(columnDefinition = "TEXT")` + Jackson `ObjectMapper` (not JPA JSON type) for H2+MySQL compatibility.

**Canonical profile — passport list** — `passports_json TEXT` stores an array of passport objects; each item has `{ id, numberEnc (AES-256-GCM), country, issueDate, expiryDate, notes, documentIds[] }`. `CanonicalProfileService` encrypts each `number` → `numberEnc` on write and decrypts on read. Current passport = item with highest `issueDate`. On every save the current passport's fields are also written to legacy single columns (`passport_number_enc`, `passport_country`, etc.) so `FormMappingService` keeps working without change.

**Canonical profile — travel entries** — `travel_entries_json TEXT` stores `[{ id, portOfEntry, i94Number, entryDate, admittedUntil, visaClass, notes, documentIds[] }]`. Most-recent entry (highest `entryDate`) is synced to legacy `port_of_entry` / `i94_number` / `entry_date` columns on save for `FormMappingService`. `currentVisaType` and `currentVisaExpiry` remain as standalone columns (current status, not history).

**Canonical profile — doc vault links** — all list types (passports, travelEntries, education, employment, dependents, priorVisas) include `documentIds: Long[]` stored in their JSON items. These are plain Document IDs; no FK constraint — loose reference only (cross-feature FK rule).

**Auto-migration** — if `passports_json` or `travel_entries_json` is null but legacy single fields have data, `CanonicalProfileService.toDTO()` returns a one-item array synthesised from the legacy columns, so the UI shows existing data immediately without a manual migration step.

**Form mapping** — all USCIS/DOS field keys have `// TODO: verify field against official form instruction`. No field label, validation message, or UI copy recommends any form or gives legal advice (UPL guardrail).

**Case types** (`CaseType` enum) — H1B family: `H1B_INITIAL`, `H1B_EXTENSION`, `H1B_TRANSFER`, `H1B_AMENDMENT`; H-4 dependents: `H4`, `H4_EAD`; green card pathway: `PERM`, `I140_EB2`, `I140_EB3`, `I485`, `GC_EAD`, `GC_RENEWAL`; citizenship: `NATURALIZATION`; other: `CONSULAR`

**`ImmOrgDTO`** — includes `myMemberId: Long` (the current caller's `ImmOrgMember.id` within that org, populated only by `listMine()`; null on all other DTO calls). Used by the frontend to auto-assign the attorney to themselves when creating a case.

**`ImmigrationCaseDTO`** — includes `assignedAttorneyEmail: String` (the attorney member's `ImmOrgMember.email`; populated from `ImmOrgMemberRepository` at DTO build time). Populated alongside `assignedAttorneyName`.

**`ImmigrationCase` key fields** — `parentCaseId Long` (nullable, H4/H4-EAD links to primary H1B case); `i140Approved boolean` (auto-set to true when an I140_EB2/I140_EB3 case reaches `PETITION_APPROVED`); `i140ApprovedDate LocalDate`; `assignedAttorneyMemberId Long` (which `ImmOrgMember` within the law firm is handling this case); `beneficiaryInviteToken String` (UUID, unique, cleared on acceptance); `beneficiaryInviteEmail String` (cleared on acceptance)

**Org-member case creation** — `CaseService.create()` requires the caller to be an active `ImmOrgMember` (EMPLOYER or LAW_FIRM org); `beneficiaryEmail` is required in `CreateCaseRequest`. If no user exists with that email, a **stub user** is created (`googleId = "PENDING_" + UUID`, `email = beneficiaryEmail`). A `Beneficiary` is created for that user, grants assigned, and an invite email sent with the `beneficiaryInviteToken`.

**Case creation rules by role**: Attorney creates → law firm auto-set to own firm, self auto-assigned as attorney, employer is required (from partnership); Employer creates → employer auto-set to own org, law firm required (from partnership), attorney optional (law firm assigns internally).

**Case number counter** — `CaseService.caseCounter` is a non-static `AtomicInteger` initialized via `@PostConstruct` from the DB max sequence for the current year. Prevents unique-constraint collisions after backend restarts.

**Stub user merge** — `CustomOAuth2UserService.loadUser()` falls back to `findByEmail()` after `findByGoogleId()` misses; if the found user has `googleId.startsWith("PENDING_")`, the stub is updated with the real Google ID. This silently links the beneficiary invite to their real Google account on first login.

**H4-EAD validation** — `CaseService.create()` checks that `parentCase.i140Approved == true` before allowing `H4_EAD` case creation; throws if not yet approved.

**Beneficiary invite join** — public `GET /api/immigration/cases/join/{token}` returns minimal case info (no auth); auth-required `POST /api/immigration/cases/join/{token}` validates caller email against `beneficiaryInviteEmail`, re-points grants from stub user to real user, clears invite fields.

**Case number** — auto-generated in `CaseService.create()` as `IMM-{year}-{seqN}`.

**Backend routes:**
- `POST /api/immigration/cases` — create (201); org-member only; `beneficiaryEmail` required
- `GET  /api/immigration/cases` — list accessible cases
- `GET  /api/immigration/cases/{id}` — detail (403 if no grant)
- `PUT  /api/immigration/cases/{id}/status` — transition status (state machine validates; auto-sets `i140Approved` on I140 cases)
- `GET  /api/immigration/cases/{id}/beneficiary/profile` — returns beneficiary's `CanonicalProfile`; requires READ_CASE grant (attorney / HR admin); calls `CanonicalProfileService.getForCase()`
- `GET  /api/immigration/cases/join/{token}` — public; minimal case info for invite page
- `POST /api/immigration/cases/join/{token}` — auth required; beneficiary accepts invite; email-match validated
- `GET  /api/immigration/profile/me` — own canonical profile
- `PUT  /api/immigration/profile/me` — update own profile
- `GET  /api/immigration/cases/{id}/forms` — list form instances
- `GET  /api/immigration/cases/{id}/forms/{formId}` — single form
- `POST /api/immigration/cases/{id}/forms/generate` — upsert forms from profile (201)
- `PUT  /api/immigration/cases/{id}/forms/{formId}/status` — update form status
- `GET  /api/immigration/cases/{id}/timeline` — merged events + appointments
- `POST /api/immigration/cases/{id}/timeline/events` — add event
- `DELETE /api/immigration/cases/{id}/timeline/events/{eventId}` — delete (non-system only)
- `POST /api/immigration/cases/{id}/timeline/appointments` — add appointment
- `DELETE /api/immigration/cases/{id}/timeline/appointments/{apptId}` — delete
- `GET  /api/immigration/cases/{id}/key-dates` — key dates for case
- `POST /api/immigration/cases/{id}/key-dates/sync` — sync from profile
- `POST /api/immigration/cases/{id}/key-dates` — add manual key date
- `DELETE /api/immigration/cases/{id}/key-dates/{keyDateId}` — remove
- `GET  /api/immigration/cases/{id}/feed` — activity feed (role-filtered)
- `GET  /api/immigration/cases/{id}/consent` — consent log (READ_CASE)
- `POST /api/immigration/cases/{id}/consent/grant` — grant consent (beneficiary only)
- `POST /api/immigration/cases/{id}/consent/revoke` — revoke consent + revokes matching Grants (beneficiary only)
- `GET  /api/immigration/cases/{id}/messages/{channel}` — messages for channel (MESSAGING scope + channel access)
- `POST /api/immigration/cases/{id}/messages/{channel}` — send message

**Org partnership routes** (`/api/immigration/partnerships`):
- `POST /api/immigration/partnerships` — direct partnership (both orgs already exist; caller must be member of one)
- `GET  /api/immigration/partnerships/mine` — list partnerships for caller's orgs
- `PUT  /api/immigration/partnerships/{id}/accept` — accept (caller must be member of the other org)
- `PUT  /api/immigration/partnerships/{id}/end` — end (member of either org)
- `POST /api/immigration/partnerships/invite` — attorney invites employer by email; creates PENDING row with `inviteEmail` + `inviteToken`; sends onboarding link; `employerOrgId` is null until onboarding completes
- `GET  /api/immigration/partnerships/onboard/{token}` — public; returns law firm name + invite email for the landing page
- `POST /api/immigration/partnerships/onboard/{token}` — auth required; employer completes their org profile; validates `caller.email == partnership.inviteEmail` (403 on mismatch); creates or reuses EMPLOYER org; sets `employerOrgId`, activates partnership

**Employer onboarding email-match** — same pattern as `POST /api/immigration/cases/join/{token}` (beneficiary invite). The throw message starts with `"Access denied:"` so `OrgPartnershipController.denied()` returns 403, not 400. Locally this always fails because `LocalDevSecurityFilter` injects `dev@localhost.local` — see CLAUDE.local.md for how to test.

**FEAT-M1 — Automated deadline reminders** (`KeyDateReminderService`):
- Entity: `KeyDateReminder` → `imm_key_date_reminders`; `@ManyToOne(LAZY)` to `KeyDate`; unique constraint on `(key_date_id, days_before_date, recipient_email)` prevents duplicate sends across restarts
- `@Scheduled(cron = "0 0 8 * * *")` daily job; buckets: `{90, 60, 30, 14, 7, 1}` days
- For each key date in the 90-day window: if `daysUntil <= bucket` and no existing reminder row → send + persist; each (key date × bucket × recipient) is sent exactly once
- Recipients: assigned attorney (always); beneficiary CC'd when `CanonicalProfile.notificationEmailEnabled = true`
- `@EnableScheduling` added to `ReceiptTrackerApplication` to activate all `@Scheduled` jobs

**FEAT-M2 — RFE structured workflow** (`CaseRfeService`, `CaseRfeController`):
- Entity: `CaseRfe` → `imm_case_rfes`; `@ManyToOne(LAZY)` to `ImmigrationCase`; status enum string: `OPEN | RESPONDED | WITHDRAWN | DISMISSED` (default `OPEN`); `respondedAt` nullable; `createdByUserId` plain `Long`
- `create()`: WRITE_CASE + `requireAttorneyInFirm()`; default deadline = issuedDate + 87 days; upserts `PETITION_DEADLINE` `KeyDate` (find-or-create to prevent duplicates)
- `update()`: null fields not applied (patch semantics); `respond()`: throws if already `RESPONDED`; sets `respondedAt = now()`
- `CaseRfeDTO.daysUntilDeadline` computed via `ChronoUnit.DAYS.between(today, responseDeadline)`
- Routes (all under `/api/immigration/cases/{caseId}/rfe`):
  - `GET  /` — list RFEs for case (READ_CASE)
  - `POST /` — create RFE (WRITE_CASE + attorney only)
  - `PUT  /{rfeId}` — update metadata (WRITE_CASE + attorney only)
  - `PUT  /{rfeId}/respond` — mark responded (WRITE_CASE + attorney only)

**FEAT-M3 — USCIS receipt number polling** (`UscisPollingService`, `UscisPollingController`):
- Entity: `UscisPollResult` → `imm_uscis_poll_results`; `caseId` is a plain `Long` (loose ref — cross-feature FK rule, same as `OrgOrder.receiptId`)
- `@Scheduled(cron = "0 0 9 * * *")` daily job; finds all cases via `findByReceiptNumberIsNotNull()`
- HTTP POST to `https://egov.uscis.gov/casestatus/landing.do`; form body `appReceiptNum=...&caseStatusSearchBtn=CHECK+STATUS`; uses `java.net.http.HttpClient` (no Spring config); 10s connect timeout, 15s request timeout
- Status extracted via regex `(?i)<h4[^>]*>\s*(Case\s[^<]{3,200})\s*</h4>` — brittle if USCIS changes HTML layout; treat as best-effort
- On detected status change: saves system `CaseEvent` (`EventType.STATUS_CHANGED`) + emails attorney via `EmailService.sendSimpleEmail()`
- Routes:
  - `GET  /api/immigration/cases/{caseId}/uscis-status-history` — poll history (READ_CASE)
  - `POST /api/immigration/cases/{caseId}/uscis-check-now` — on-demand poll (READ_CASE + `requireAttorneyInFirm()`)

**FEAT-M7 — Intake questionnaire / client data collection** (`ProfileDataRequestService`, `DataRequestController`):
- Entity: `ProfileDataRequest` → `imm_profile_data_requests`; `caseId` and `requestedByUserId` are plain `Long` (cross-feature FK rule); `token` UUID unique; `sectionsRequested` JSON TEXT; status `PENDING | SUBMITTED | EXPIRED`; `submittedAt` nullable; `expiresAt`, `createdAt` non-null
- `@PrePersist` sets `createdAt` and generates `token = UUID.randomUUID().toString()` if null
- Routes:
  - `POST /api/immigration/cases/{id}/data-requests` — create (ATTORNEY + PARALEGAL via `requireAccess WRITE_CASE`); body `{ targetRelationship, sections[], expiryDays }`; sends email with `{frontendUrl}/immigration/data-request/{token}`; `app.frontend.url` env var with fallback
  - `GET  /api/immigration/cases/{id}/data-requests` — list for case (ATTORNEY, READ_CASE)
  - `GET  /api/immigration/data-requests/{token}` — **public** (GET only, `permitAll()` in `SecurityConfig`); auto-expires stale PENDING rows; returns `DataRequestPublicDTO` with sections spec + prefilled `CanonicalProfileDTO` from beneficiary's profile
  - `POST /api/immigration/data-requests/{token}/submit` — auth required; validates `targetRelationship == BENEFICIARY → caller.email == case.beneficiaryEmail`; calls `CanonicalProfileService.updateForBeneficiary()` (package-private); sets status `SUBMITTED`
- `applySubmittedSections()` in service uses `ObjectMapper.convertValue(mergedMap, UpdateProfileRequest.class)` to reuse all existing encryption + persistence logic
- `CanonicalProfileService.updateForBeneficiary(Beneficiary, UpdateProfileRequest)` — package-private method (same package as `ProfileDataRequestService`); upserts profile; no public exposure needed
- `DataRequestPublicDTO`: id, caseNumber, beneficiaryName, beneficiaryEmail, targetRelationship, sections, status, expiresAt, prefillData (CanonicalProfileDTO or null)
- Email is non-fatal: caught and logged WARN if `EmailService` is not configured

**FEAT-M8 — Evidence checklist** (`ChecklistService`, `ChecklistController`, `ChecklistTemplateSeeder`):
- Entities: `ChecklistTemplate` → `imm_checklist_templates` (form_type, item_key, label, category, required, condition_rule JSON TEXT nullable, sort_order); `ChecklistItem` → `imm_checklist_items` (case_id loose Long, template_id loose Long nullable, item_key, label, category, required, status, document_id loose Long nullable, waiver_reason TEXT nullable, verified_by_user_id loose Long nullable, verified_at, sort_order, created_at, updated_at)
- Status lifecycle: `PENDING → UPLOADED → VERIFIED`; `PENDING → WAIVED` (attorney/paralegal only, reason required)
- Condition rule: `ChecklistService.evaluateCondition()` parses JSON rule `{"caseTypeIn":["I485"]}` or `{"i140Approved":true}`; null/blank = always include
- Seeder: `ChecklistTemplateSeeder implements ApplicationRunner` — seeds I129 (13 items), I485 (11 items), I140_EB2/EB3 (9 items each), PERM (8 items) on first startup; item keys are scoped as `{formType}_{ITEM_KEY}` to prevent cross-type collisions
- `generate()` — requireAccess `MANAGE_CHECKLISTS`; loads templates for requested formTypes; evaluates condition; creates new PENDING items only for item keys not already present (preserves status of existing items)
- `update()` — requireAccess `WRITE_CASE`; VERIFIED/WAIVED additionally require `MANAGE_CHECKLISTS`; VERIFIED sets verifiedByUserId + verifiedAt from caller; WAIVED requires non-blank waiverReason
- Routes (all auth required):
  - `POST /api/immigration/cases/{caseId}/checklist/generate` — MANAGE_CHECKLISTS; body `{ formTypes[] }`
  - `GET  /api/immigration/cases/{caseId}/checklist` — READ_CASE; ordered by category + sort_order
  - `PUT  /api/immigration/cases/{caseId}/checklist/{itemId}` — WRITE_CASE (VERIFIED/WAIVED: + MANAGE_CHECKLISTS)

**Phase 4 — Canonical Question Registry + Form Packet System** (`CanonicalQuestionRegistry`, `DataResolver`):

**JSON config files** (classpath: `immigration/questions/`):
- `canonical-questions.json` — 44 questions with key, label, sublabel, owner (BENEFICIARY|EMPLOYER|ATTORNEY), dataSource (documentation path), friendlySection, type (TEXT|TEXT_SENSITIVE|DATE|NUMBER|BOOLEAN), required, validation constraints, encrypt flag, formsUsing[]
- `form-field-mappings/I129.json`, `I485.json`, `I140.json` — per-form section→fields mappings; each field entry: `{ questionKey, pdfFieldName }` where pdfFieldName is the AcroForm field name in the USCIS PDF

**Model classes** (`immigration/model/question/`): `CanonicalQuestion` (plain POJO, Jackson-deserialised), `FormFieldEntry` (record), `FormSectionMapping` (owner + fields[]), `FormFieldMapping` (formType + sections map), `ResolvedValue` record (value, source, verifiedAt) with factory methods `none()`, `fromProfile()`, `fromOrg()`, `fromCase()`

**`CanonicalQuestionRegistry` @Service**:
- `@PostConstruct init()` — loads canonical-questions.json + all form-field-mappings/*.json via `PathMatchingResourcePatternResolver`; fails gracefully (logs WARN) if files missing
- `getQuestionsForForms(List<String> formTypes)` — deduplicated ordered list (preserves JSON array order) of questions whose `formsUsing` intersects the requested set
- `getQuestionsByOwner(List<CanonicalQuestion>)` → `LinkedHashMap<String, List<CanonicalQuestion>>` keyed by owner
- `getQuestionsBySection(List<CanonicalQuestion>)` → `LinkedHashMap<String, List<CanonicalQuestion>>` keyed by friendlySection
- `getOrderedFieldsForForm(String formType)` → flat ordered `List<FormFieldEntry>` across all sections (for PDF fill layer)
- `findByKey(String)` → `Optional<CanonicalQuestion>`; `getFormMapping(String)` → `Optional<FormFieldMapping>`
- `sectionLabel(String sectionId)` — static lookup; 10 friendly section IDs → display labels

**`DataResolver` @Service**:
- `ResolutionContext` record — bundles `CanonicalProfile profile`, `ImmOrg employerOrg`, `ImmOrg lawFirmOrg`, `ImmigrationCase immigrationCase`, `AttorneyProfile attorneyProfile`; any field may be null
- `resolve(CanonicalQuestion, ResolutionContext)` → `ResolvedValue` — dispatches via `switch` on questionKey; never throws (logs WARN, returns `none()`)
- `resolveAll(List<CanonicalQuestion>, ResolutionContext)` → `Map<String, ResolvedValue>` — one-pass resolution for all questions; keys are questionKey strings
- Routing logic:
  - `beneficiary.*` → `CanonicalProfile` fields; encrypted fields (passportNumber, i94Number, alienNumber, ssn, eadCardNumber) decrypted via `EncryptionService.decrypt()`; I-94 prefers `i94NumberEnc`, falls back to legacy `i94Number` plain column
  - `beneficiary.address*` → parsed from `currentAddressJson` (Map `{line1,city,state,zip,country}`)
  - `job.*` → first entry of `employmentJson` array for title/startDate; `job.socCode`/`salaryAmount`/`hoursPerWeek` return `none()` (no backing field yet)
  - `employer.*` → `ImmOrg employerOrg` fields
  - `attorney.firmName` / `attorney.email` → `ImmOrg lawFirmOrg` fields; `attorney.barNumber` → first entry of `AttorneyProfile.barNumbersJson[0].barNumber`
- **No DB calls** in DataResolver — callers load entities before calling resolve()

**Friendly section IDs** (10): `personal_info`, `passport_id`, `current_status`, `company_info`, `job_details`, `employment_history`, `education`, `family_dependents`, `ead_info`, `notification_prefs`

**Filing Package System** (`FilingPackageService`, `FilingPackageController`):
- **Entities**: `FilingPackage` → `imm_filing_packages` (caseId loose Long, name, selectedFormTypesJson TEXT, status DRAFT|QUESTIONNAIRES_SENT|ANSWERS_COLLECTED|ATTORNEY_REVIEW|APPROVED|GENERATED|FILED, approvedByUserId, approvedAt, generatedPdfPacketKey, createdByUserId); `FilingPackageQuestionnaire` → `imm_filing_package_questionnaires` (FilingPackage FK, targetRelationship BENEFICIARY|EMPLOYER|ATTORNEY, token UUID unique, questionnaireSpecJson TEXT = JSON array of question keys, status PENDING|SUBMITTED|EXPIRED, submittedAt, submittedByUserId loose Long, submittedAnswersJson TEXT, expiresAt); `FilingPackageAnswer` → `imm_filing_package_answers` (FilingPackage FK, questionKey, valueJson TEXT, valueHash VARCHAR(64) SHA-256 of plaintext, owner, source profile|org|questionnaire|attorney_override, verifiedAt, answeredByUserId, attorneyOverrideReason TEXT); unique constraint on (package_id, question_key)
- **Sensitive field storage**: `valueJson` stores AES-256-GCM ciphertext for encrypt=true questions; `valueHash` stores SHA-256 hex of plaintext for comparison without decryption; non-sensitive fields store plaintext in `valueJson`
- **Create flow**: MANAGE_CHECKLISTS scope → load questions via CanonicalQuestionRegistry → resolve prefills via DataResolver → save FilingPackageAnswer rows (source="profile"|"org") → create one FilingPackageQuestionnaire per owner group; questionnaire `questionnaireSpecJson` = JSON array of that owner's question keys
- **Send questionnaires**: sets status → QUESTIONNAIRES_SENT; sends `sendSimpleEmail()` to beneficiary (case.beneficiary.user.email), employer (ImmOrg.contactEmail), attorney (ImmOrgMember.email for assignedAttorneyMemberId)
- **Public GET** (`/api/immigration/packages/questionnaires/{token}` — SecurityConfig permitAll GET): returns `QuestionnairePublicDTO` with sections + questions; sensitive (encrypt=true) questions always have null prefillValue; loads current answer values from FilingPackageAnswer for non-sensitive prefills
- **Submit** (auth required): validates caller email vs case.beneficiary.user.email (BENEFICIARY), ImmOrg member/contactEmail (EMPLOYER), or assignedAttorneyMember.email (ATTORNEY); upserts FilingPackageAnswer rows (source="questionnaire"); writes back to CanonicalProfile (BENEFICIARY) or ImmOrg (EMPLOYER); auto-transitions package → ANSWERS_COLLECTED when all questionnaires submitted
- **Write-back to CanonicalProfile** (BENEFICIARY submit): updates plain text fields directly on entity; encrypted fields go through EncryptionService; JSON sub-fields (address, employment) are NOT written back (partial JSON patching is unsafe)
- **Attorney override**: APPROVE_FORMS scope; upserts FilingPackageAnswer with source="attorney_override"; stores overrideReason
- **Review summary**: groups answers by owner with completeness %; flags: missing required (no value), stale (source=profile|org and verifiedAt null or >90 days old)
- **Approve**: APPROVE_FORMS scope; sets status → APPROVED, records approvedByUserId + approvedAt
- **Endpoints**: POST/GET/GET `/cases/{id}/packages[/{packageId}]`, POST `…/{packageId}/send-questionnaires`, GET `…/{packageId}/review-summary`, POST `…/{packageId}/approve-answers`, PUT `…/{packageId}/answers/{answerKey}`, GET/POST `/packages/questionnaires/{token}[/submit]`
- **SecurityConfig**: added `GET /api/immigration/packages/questionnaires/**` to permitAll in production profile

**Form Version Tracking** (`FormVersionService`, `FormVersionController`, `FormVersionScheduler`):
- **Entities**: `FormVersion` → `imm_form_versions` (formType VARCHAR(50), editionDate VARCHAR(20), downloadedAt, pdfStorageKey VARCHAR(500), status PENDING_REVIEW|APPROVED|DEPRECATED, approvedByUserId loose Long, approvedAt, fieldMappingVerified BOOLEAN, pdfFieldNamesJson TEXT, releaseNotes TEXT, proposedMappingJson TEXT); `FormVersionAuditEvent` → `imm_form_version_audit` (formType, editionDate, action DOWNLOADED|APPROVED|DEPRECATED|MAPPING_UPDATED|CHECK_NO_CHANGE|CHECK_ERROR, performedByUserId loose Long, detail TEXT)
- **Scheduler** (`FormVersionScheduler`): `@Scheduled(cron = "0 0 1 1 * ?")` — first day of each month at 1am; delegates to `FormVersionService.checkForUpdates()`; catches all exceptions per-form so one failure doesn't abort the entire check
- **checkForUpdates()**: fetches `https://www.uscis.gov/forms/all-forms` via Java `HttpClient`; regex-parses edition date near form number (e.g. "I-129"); on new edition: downloads PDF from `https://www.uscis.gov/sites/default/files/document/forms/{lower}.pdf`, extracts AcroForm field names via PDFBox `Loader.loadPDF()` + `PDAcroForm.getFields()`, stores PDF under `{storagePath}/form-versions/{formType}/{edition}.pdf`, saves `FormVersion` (status=PENDING_REVIEW), notifies all ATTORNEY+OWNER members of all LAW_FIRM ImmOrgs via `sendSimpleEmail()`
- **Access control** (`requireAttorneyOrOwner()`): NOT case-scoped — checks that caller is an ACTIVE ImmOrgMember of any LAW_FIRM org with role ATTORNEY or OWNER; does NOT use PermissionService
- **approve()**: validates status=PENDING_REVIEW + fieldMappingVerified=true; deprecates current APPROVED row; sets status=APPROVED + approvedByUserId/At; saves audit
- **uploadMapping()**: validates JSON parseable as `FormFieldMapping`; checks all `questionKey` values exist in `CanonicalQuestionRegistry`; stores JSON in `proposedMappingJson`; sets `fieldMappingVerified=true`
- **FORM_NUMBERS map**: static map from FormType.name() → USCIS form number string; DS160 omitted (DOS form, not USCIS)

**PDF Generation Service** (`ImmPdfGenerationService`, endpoints on `FilingPackageController`):
- **Entity**: `GeneratedPdfPacket` → `imm_generated_pdf_packets` (packageId loose Long, caseId loose Long, formVersionsUsedJson TEXT = `[{formType, versionId, editionDate}]`, generatedAt, generatedByUserId loose Long, status DRAFT|ATTORNEY_APPROVED|FILED, attorneyApprovedAt, attorneyApprovedBy loose Long, pdfStorageKey VARCHAR(500) relative from storageRoot e.g. `pdf-packets/{caseId}/{uuid}.zip`, generationAuditJson TEXT = `[{questionKey, pdfField, source, versionId, filled, formType}]`)
- **Pre-generation checks** (all must pass): 1) package status=APPROVED; 2) each formType has an APPROVED FormVersion with fieldMappingVerified=true; 3) no newer PENDING_REVIEW version (409 CONFLICT with `PENDING_REVIEW_EXISTS:` prefix if any — frontend shows override prompt); 4) all required FilingPackageAnswer rows present and non-blank; 5) all required ChecklistItem rows are UPLOADED or WAIVED
- **PDF fill loop**: loads PDF from `storageRoot/{formVersion.pdfStorageKey}` via `Loader.loadPDF(bytes)` (PDFBox 3.x); for each field entry in `form-field-mappings/{formType}.json`, resolves FilingPackageAnswer; decrypts if `CanonicalQuestion.isEncrypt()`; calls `PDField.setValue()`; clears plaintext from memory immediately; calls `acroForm.flatten()` after all fills; audit entry captured per field
- **Cover sheet**: PDFBox `PDDocument` + `PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD/HELVETICA)`; contains case number, date, generated-by name, forms+editions table, attorney signature line
- **ZIP**: `java.util.zip.ZipOutputStream`; entries: `00_cover-sheet.pdf`, `01_{formType}_{edition}.pdf`, …; stored at `storageRoot/pdf-packets/{caseId}/{uuid}.zip`
- **approve()**: APPROVE_FORMS scope; idempotent; sets status→ATTORNEY_APPROVED; writes `FormVersionAuditEvent` action=PACKET_APPROVED per form
- **Endpoints**: POST `…/generate-pdf` body `{overridePendingReview: bool}`; GET `…/packets`; GET `…/packets/{id}/download` streams ZIP; POST `…/packets/{id}/approve`; all require APPROVE_FORMS scope

**Document Scan Service** (`ImmDocumentScanService`, controller `ImmDocumentScanController`):
- **Purpose**: AI-powered document scanning that extracts fields for user review — **NEVER saves data**, returns only
- **Reuses from `ClaudeVisionService`** (made public): `renderPdfToImages(File)`, `buildRequestBody(images, mediaType, prompt)`, `callAnthropicApi(String)`, `readImageBytes(File)`, `detectImageMediaType(String)`, `isReadyForVision()`
- **Detection**: first pass sends `DOCUMENT_TYPE_DETECTION_PROMPT` → one of: `PASSPORT | US_VISA_STAMP | I94_PRINTOUT | I797_NOTICE | I20_FORM | EAD_CARD | I140_APPROVAL | DS2019 | UNKNOWN`
- **Extraction**: second pass with doc-type-specific JSON prompt; each prompt includes a `confidence` map per field (0.0–1.0)
- **`ScanResult` DTO**: `docTypeDetected`, `extractedFields: Map<String, FieldExtraction>`, `lowConfidenceFields: List<String>`, `caseReceiptNumberSuggestion` (non-null for I797 only)
- **`FieldExtraction` record**: `value`, `confidence`, `needsReview` (= confidence < 0.85)
- **Profile scan** (`POST /api/immigration/profile/scan`): any authenticated user; validates file (PDF/JPG/PNG, ≤ 10 MB)
- **Case scan** (`POST /api/immigration/cases/{id}/scan-document`): requires WRITE_CASE grant; same validation; populates `caseReceiptNumberSuggestion` for I-797
- **503 if Vision AI disabled** (`isReadyForVision()` = false — ANTHROPIC_API_KEY missing or VISION_AI_ENABLED=false)
- **File handling**: saved to OS temp file → rendered → deleted in `finally` block; never persisted
- **Audit**: `scanForCase()` emits `SCANNED` event to `ImmFieldAuditEvent` with `caseId`; `scanForProfile()` emits with `caseId=null`

**Phase 7 — Audit Service Expansion**:
- **`ImmFieldAuditEvent`** → `imm_field_audit_events`: append-only field-level audit log; `case_id` is a loose nullable Long; `isSensitive=true` → stores SHA-256 hashes of old/new values, never plaintext; `isSensitive=false` → stores actual values in `detail` JSON
- **Actions**: `CHANGED | SCANNED | QUESTIONNAIRE_SUBMITTED | PDF_GENERATED | ATTORNEY_APPROVED | FORM_VERSION_APPROVED`
- **Sources**: `direct_edit | ocr_scan | questionnaire | attorney_override | system`
- **`AuditService` new methods**:
  - `appendFieldChange(caseId, entityType, entityId, fieldKey, oldVal, newVal, source, isSensitive, actorUserId)` — non-fatal; hashes sensitive, stores plain detail for non-sensitive
  - `appendCaseEvent(caseId, entityType, entityId, fieldKey, action, source, detail, actorUserId)` — for multi-field events (scan, questionnaire, PDF)
  - `appendFormVersionEvent(formType, editionDate, action, actorUserId, detail)` — delegates to `FormVersionAuditEventRepository`
  - `appendPdfGeneration(packetId, caseId, formVersionsUsed, generatedByUserId)` — one `ImmFieldAuditEvent` with `action=PDF_GENERATED`, `formVersionsUsed` in detail JSON
  - `getCaseAudit(caseId)` → `CaseAuditDTO` — grouped: `caseEvents` (ImmAuditEvent), `dataChanges` (CHANGED/SCANNED/QUESTIONNAIRE_SUBMITTED), `formVersionEvents` (form types from case packages), `pdfEvents` (PDF_GENERATED/ATTORNEY_APPROVED)
- **Call sites**: `CanonicalProfileService.updateForCurrentUser()` (per-field, `caseId=null`); `FilingPackageService.submitQuestionnaire()`; `ImmDocumentScanService.scanForCase()` + `scanForProfile()`; `ImmPdfGenerationService.generatePacket()`; `FormVersionService.approve()`
- **`CanonicalProfileService` field audit**: captures snapshot of all scalar fields before `applyUpdate()`; emits one event per changed field after save; sensitive fields (`alienNumber`, `ssn`, `i94Number`, `eadCardNumber`) use `isSensitive=true`; JSON blobs emit one aggregate `CHANGED` event each
- **`GET /api/immigration/cases/{id}/audit`** (APPROVE_FORMS scope = ATTORNEY + OWNER): returns `CaseAuditDTO`; handled by `ImmAuditController`
- **`FormVersionService.approve()`**: now calls `auditService.appendFormVersionEvent()` instead of private `saveAudit()` for the APPROVED action; `saveAudit()` still used for DEPRECATED actions

## Tests
All tests live under `src/test/java/com/receipttracker/`.

| Class | Layer | Count | Coverage focus |
|---|---|---|---|
| `OrganizationServiceTest` | Service | 30 | create, listMine, getBySlug, invite (role gates), acceptInvite (email-match, idempotency), revoke, getInviteByToken, listMembers |
| `OrganizationControllerTest` | Controller | 17 | HTTP 200/400 for all org CRUD routes |
| `OrgJoinControllerTest` | Controller | 8 | public GET + auth POST for join token |
| `DocumentServiceTest` | Service | 11 | upload validation, toDTO status, next steps, archive |
| `DocumentShareServiceTest` | Service | 8 | create share, token lookup, expiry, download |
| `ExpenseShareServiceTest` | Service | 12 | equal/custom/item-based splits, paid-for-me |
| `ReceiptGroupServiceTest` | Service | 8 | group add/remove, membership gates |

**Java 25 setup** (required for Mockito to work):
- `src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker` → `mock-maker-subclass`
  (inline mock maker can't instrument `java.lang.Object` on Java 25)
- All test classes annotated `@MockitoSettings(strictness = Strictness.LENIENT)` because
  `@BeforeEach` security-context helpers stub fields not consumed by every negative test

## Commands
```
mvn spring-boot:run -Dspring.profiles.active=local   # dev (H2, no auth)
mvn clean package                                     # build fat JAR
mvn test                                              # run tests (94 total)
```

## Don't
- Don't use constructor injection — field injection (`@Autowired`) is used everywhere
- Don't change `ddl-auto` to `create`/`create-drop` in prod — data loss
- Don't store raw S3 credentials — encrypt via `EncryptionService`
- Don't skip `orphanRemoval=true` when a new `@OneToMany` needs clear-and-replace update semantics
- Don't add JWT — the whole frontend relies on session cookies
- Don't hardcode cashback rates outside `CashbackService.CARD_RATES`
- Don't run as profile `local` in prod — it disables all auth
- Don't use `float`/`double` for money — use `BigDecimal`
- Don't use `requestMatchers("/api/shares/token/**").permitAll()` — must scope to GET only to keep POST action endpoint auth-protected
- Don't let `p.waitFor()` block indefinitely — always use `p.waitFor(N, TimeUnit.SECONDS)` with `p.destroyForcibly()` on timeout
- Don't add JSON sub-field write-back (address, employment, education JSON columns) in `FilingPackageService.writeBackAnswers()` — partial JSON patching is unsafe; only plain scalar columns are written back
- Don't add cascades from `FilingPackage` → `FilingPackageQuestionnaire` / `FilingPackageAnswer` — kept separate to allow independent lifecycle management (e.g. re-generate without wiping answers)
- Don't use `PermissionService.requireAccess()` in `FormVersionService` — form versions are not case-scoped; use `requireAttorneyOrOwner()` which checks ImmOrgMember role in any LAW_FIRM org
- Don't use Jsoup for USCIS page parsing — regex on raw HTML avoids adding a dependency; scraping is best-effort and all failures are caught/logged without aborting the scheduler
- Don't store raw sensitive values (passport number, SSN, A-number, I-94, EAD card) in `ImmFieldAuditEvent.detail` — always use `isSensitive=true` so only SHA-256 hashes are persisted
- Don't add update/delete methods to `ImmFieldAuditEventRepository` — it is append-only like `ImmAuditEventRepository`
- Don't call `appendFieldChange()` / `appendCaseEvent()` inside `@Transactional` boundaries where a failure would roll back data saves — all AuditService append methods are non-fatal (catch-and-log), so they are safe to call within the same transaction
