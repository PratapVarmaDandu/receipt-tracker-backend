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
