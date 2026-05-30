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
- Equal split divides by `invitees.size() + 1` — the +1 accounts for the owner's share
- Public endpoint `GET /api/shares/token/**` is `permitAll()` for GET only — POST requires auth
  (enforced with `requestMatchers(HttpMethod.GET, "/api/shares/token/**")` in SecurityConfig)
- Email notifications: invite → invitee; change-request → owner; approve/reject → invitee;
  **accept/deny → owner** (owner gets notified whenever invitee takes any action)
- Email: `EmailService` uses `@Autowired(required=false) JavaMailSender` — if no SMTP config,
  falls back to `log.warn`; email failures are never propagated (non-fatal)
- Requires `GMAIL_USERNAME` + `GMAIL_APP_PASSWORD` env vars for real email delivery

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
