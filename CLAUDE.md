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
- `Receipt.vehicle` — nullable `@ManyToOne Vehicle`; set via `PUT /api/receipts/{id}/vehicle`; `ReceiptDTO` exposes `vehicleId` + `vehicleName` (computed: `"{year} {make} {model}"`)
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
- `splitType` field on `ExpenseShare`: `EQUAL`, `CUSTOM`, or `ITEM_BASED`
- Equal split divides by `invitees.size() + 1` — the +1 accounts for the owner's share
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
  GET /api/groups/:id/members

## Vision AI config
- Feature-flagged: `vision.ai.enabled=${VISION_AI_ENABLED:false}`
- Set `ANTHROPIC_API_KEY` + `VISION_AI_ENABLED=true` in `local.env` to enable
- PDFs are rendered page-by-page to PNG before sending (up to `vision.ai.max-pdf-pages`, default 5)
- Model: `claude-sonnet-4-6` (override with `VISION_AI_MODEL`)

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

## Commands
```
mvn spring-boot:run -Dspring.profiles.active=local   # dev (H2, no auth)
mvn clean package                                     # build fat JAR
mvn test                                              # run tests
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
