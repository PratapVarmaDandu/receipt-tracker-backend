# USCIS Field Completeness & Questionnaire Scoping — Multi-Session Plan

Status: **ALL PLANNED SESSIONS DONE (2026-07-02/03).** Session 1 DONE. Session 2 DONE. Session 3
DONE. Session 4a (I-765) DONE. Session 4b (G-28) DONE. Session 4c (I-131) DONE. Session 4d
(I-539) DONE. Session 4e (I-290B) DONE. Session 4f (I-693) DONE. **This closes out every form in
the original Session 4 list** (I-765, I-131, I-539, G-28, I-290B, I-693) — see "What's left" at
the bottom of this file for optional follow-on work; nothing is currently blocking or required.

Entity columns + full canonical-questions.json catalog growth landed in Session 1. Session 2
replaced every placeholder `pdfFieldName` in `form-field-mappings/I129.json` with real AcroForm
field names extracted from the actual USCIS PDF, added H Classification + H-1B Data Collection
Supplement mappings, validated via the app's own `upload-mapping` endpoint, and approved
FormVersion id 33 (the real PDF) so it's the active version PDF generation uses. Session 3 added
an exactness test for the questionnaire scoping contract and confirmed (no code fixes needed)
that every field added in Sessions 1-2 has the correct owner and a clean label/section. Sessions
4a-4f each repeated the whole recipe (real user-supplied PDF → extract fields → map onto the
canonical catalog → validate + approve via the app's own endpoints → mvn test) for I-765 (EAD),
G-28 (attorney appearance), I-131 (travel/parole documents, scoped to Reentry Permit + Advance
Parole), I-539 (extend/change status, the largest single addition), I-290B (appeal/motion), and
I-693 (medical exam, heavily scoped down to exclude clinical worksheet/vaccination-grid detail)
— see their own subsections under Session 4 below for the field-by-field decisions.

**Final catalog size: 206 canonical questions** (started at 60 pre-Session-1, grew to 117 after
Session 1, 136/152/161/196/202/206 after 4a/4b/4c/4d/4e/4f respectively). **9 form mappings**:
I129, I140, I485 (pre-existing) + I765, G28, I131, I539, I290B, I693 (this multi-session effort).

Tests: 176 total throughout every session, only the same pre-existing unrelated
`FeatureEntitlementServiceTest` failures (missing `@Mock` for `userFeatureRepo`) — never touched
by this work, confirmed still isolated as of the final session.
`I129-FIELD-INVENTORY.md` has the full Part-by-Part field extraction for I-129 and is still
accurate; Session 2/3's own decisions/gaps are recorded inline below; I-765, G-28, I-131, I-539,
I-290B, and I-693's field-by-field decisions are recorded inline in their own Session 4
subsections (no separate inventory file was needed since extraction and mapping happened in the
same session as each form).

## Goal
1. Grow the canonical field catalog (employer profile + beneficiary profile) so it captures
   everything real USCIS forms need — starting with I-129, since that's the form in active use.
2. Guarantee per-case questionnaire scoping is exact: a beneficiary/employer filling out a
   questionnaire for a case sees **only** the fields required by that case's selected form
   types — nothing extra, nothing missing.

## Architecture facts already confirmed — do not re-derive these
- Adding a field to a form is **config-only**: edit `canonical-questions.json` +
  `form-field-mappings/{Form}.json`. No Java changes needed unless the field needs a new
  entity column (employer/beneficiary profile) or a new `DataResolver` switch arm.
- Per-case scoping mechanism already exists and is correct by construction:
  `CanonicalQuestionRegistry.getQuestionsForForms(selectedFormTypes)` intersects each
  question's `formsUsing[]` with the case's selected forms. `FilingPackageService.create()`
  then groups by `owner` (BENEFICIARY/EMPLOYER/ATTORNEY) into one questionnaire per party.
  → **The "nothing else / nothing less" requirement is a data-tagging correctness problem,
  not an architecture problem.** Get the `formsUsing[]` and `owner` tags right and scoping
  falls out for free.
- `reviewOnly: true` questions (attorney attestations) and `derivation: "..."` questions
  (computed, e.g. classificationSymbol) are automatically excluded from ALL questionnaires —
  verify this still holds after adding new fields, don't reintroduce them by accident.
- Adding a brand-new form (I-765/I-131/I-539/G-28/I-290B/I-693) is a separate, later effort —
  see "Session 4" below. Don't mix that work into the I-129 cleanup.

## Current state snapshot (updated 2026-07-02 after Session 1 — re-check only if code has changed since)

**FormType enum** (11 total): I129, DS160, I485, I765, I131, I140, I539, G28, I290B, I693, PERM

**canonical-questions.json**: 117 questions (40 BENEFICIARY, 45 EMPLOYER, 32 ATTORNEY; 48
generic-storage, 4 derived, 14 review-only). `formsUsing` coverage: I129 now covers all Session 1
additions; I140/I485/PERM/DS160 referenced elsewhere; I765/I131/I539/G28/I290B/I693 = **0
questions tagged** (still out of scope). `beneficiary.gender`/`beneficiary.ssn` now include
`"I129"`. `signatory.fullName` was **removed**, replaced by `signatory.lastName` +
`signatory.firstName` (both reviewOnly TEXT, ATTORNEY) — no PDF mapping existed for it, so this
was a safe schema change.

**form-field-mappings/**: `I129.json` (Parts 1, 3, 5, 6 only), `I140.json`, `I485.json` —
**unchanged by Session 1** (config-only field/question additions don't need mapping entries
until Session 2). Real I-129 has Parts 1-9 — Parts 2, 4, 7, 8, 9 remain unmapped; the new
questions from Part 2/4/5/7 and both H supplements have no `pdfFieldName` yet.

**CanonicalProfile entity** (`receipt-tracker-backend/src/main/java/com/receipttracker/immigration/model/CanonicalProfile.java`):
name/DOB/birth country/citizenship/gender, passport fields (encrypted), I-94, alien number
(encrypted), SSN (encrypted, `ssn_enc`), EAD fields, current visa, phone, notification prefs,
USCIS account number, address/education/employment/dependents/priorVisas/passports/travel
JSON blobs, **plus new `province_of_birth` and `sevis_number` columns (Session 1)**.

**ImmOrg entity** (`.../model/ImmOrg.java`): name, `orgType` (enum `EMPLOYER|LAW_FIRM` —
this is NOT profit/nonprofit, it's law-firm-vs-employer), ownerUserId, contactName, contactEmail
(now resolved by `employer.email`), address/city/stateCode/zipCode, einNumber, website, **plus
new columns (Session 1): `country`, `nonprofitOrGovResearch` (Boolean), `businessType`,
`yearEstablished` (Integer), `employeeCount` (Integer), `grossAnnualIncome`/`netAnnualIncome`
(BigDecimal), `smallEmployerFlag` (Boolean)**. All new columns are nullable; `ddl-auto=update`
creates them automatically on next local/prod boot — no manual migration needed.

**DataResolver**: new typed switch arms added for `beneficiary.provinceOfBirth`,
`beneficiary.sevisNumber`, and 9 `employer.*` fields (`email`, `country`,
`nonprofitOrGovResearch`, `businessType`, `yearEstablished`, `employeeCount`,
`grossAnnualIncome`, `netAnnualIncome`, `smallEmployerFlag`); two new helpers `orgBool()` /
`orgNum()` alongside the existing `orgStr()`. Every other Session 1 addition uses
`storage: "generic"` (no DataResolver change needed) — H Classification Supplement,
H-1B Data Collection Supplement, Part 2/4/5 petition/job fields, and beneficiary education
fields are all generic-store questions. `subjectScope: "CASE"` was set explicitly wherever a
value is per-petition rather than per-org/per-beneficiary (e.g. `job.*`, most new `petition.*`
fields); the 8 H-1B Data Collection employer-characteristic flags use the **default**
`EMPLOYER_ORG` scope since they describe the employer itself, not a specific case.

## Confirmed gaps, Part-by-Part (I-129 only)

**Superseded by `I129-FIELD-INVENTORY.md`** — that file has the exhaustive real-form
extraction (every item number, every checkbox, every existing-vs-new mapping) for Parts 1-9
plus the H Classification Supplement and the H-1B/H-1B1 Data Collection Supplement. Read it
before starting Session 1 or 2. Rough summary (see inventory file for detail):
- Part 1: `employer.email`/`country`/nonprofit-boolean missing (nonprofit is a **simple
  boolean**, not the full cap-exempt set — resolved, see below)
- Part 2: 0 PDF mappings; new `petition.mostRecentReceiptNumber` field; Items 2 & 4 are
  6-option **checkboxes**, not free text — the existing derived TEXT questions are fine for
  storage but need `checkboxOnValue` mapping in Session 2
- Part 3: `beneficiary.gender`/`ssn` need `"I129"` added to `formsUsing`; **province of birth
  DOES exist on the real form (Item 5) — add `beneficiary.provinceOfBirth`**; new
  `beneficiary.sevisNumber` field (relevant to the earlier OPT/STEM-OPT discussion)
- Part 4: 0 PDF mappings; several fields need "how many" counts added; Item 8 splits into two
  booleans (granted vs. denied) where only one exists today
- Part 5: only 5 of ~17 real fields exist — LCA/ETA case number, worksite address(es),
  full/part-time, wages "per" unit, employment end date, business type/year/employee
  count/income are all missing
- Part 6: no new question needed, just Session 2 mapping (design already anticipated this)
- Part 7: 0 PDF mappings; signatory name may need Family/Given split (real form asks
  separately); signatory phone/email missing
- Part 8 (preparer): entirely unmodeled, low priority, conditional section
- Part 9: free-form overflow sheet — recommend not modeling as questions
- **H Classification Supplement** (new scope, required for every H filing): H-1B registration
  confirmation number, proposed duties description, beneficiary's present occupation summary —
  all high-value, all currently missing
- **H-1B Data Collection Supplement** (new scope, required for every H-1B): education level,
  field of study, NAICS code, position requirements, cap type/wage level — high-value, missing

## Session plan

### Session 1 — Entity + catalog gaps (employer + beneficiary universal fields) — ✅ DONE 2026-07-02
Config + minimal Java (new entity columns, one `DataResolver` switch arm each). No PDF
mapping work yet — blocked separately on Session 2. Full field list with proposed keys is in
`I129-FIELD-INVENTORY.md` — this checklist is the condensed action list.
- [x] `ImmOrg`: add `country` column (nullable)
- [x] `ImmOrg`: add `nonprofitOrGovResearch` boolean (**resolved** — real form Part 1 Item 6 is
      one simple Yes/No, not the full cap-exempt category set)
- [x] `ImmOrg`: add businessType, yearEstablished, employeeCount, grossAnnualIncome,
      netAnnualIncome, smallEmployerFlag (Part 5 Items 12-17)
- [x] `CanonicalProfile`: add `provinceOfBirth` column (**real field, Part 3 Item 5** — earlier
      assumption that it didn't exist was wrong, corrected via the actual PDF)
- [x] `CanonicalProfile`: add `sevisNumber` column (Part 3 Item 6)
- [x] Canonical questions: `employer.email` (→ existing `ImmOrg.contactEmail`), `employer.country`,
      `employer.nonprofitOrGovResearch`, `employer.businessType/yearEstablished/employeeCount/
      grossAnnualIncome/netAnnualIncome/smallEmployerFlag`, `beneficiary.provinceOfBirth`,
      `beneficiary.sevisNumber`
- [x] Fix `formsUsing` on `beneficiary.gender` and `beneficiary.ssn` → add `"I129"`
- [x] Add `petition.mostRecentReceiptNumber` (Part 2 Item 3) — **implemented with owner
      BENEFICIARY** per the resolved decision below (not the ATTORNEY-leaning note this
      bullet originally had); generic storage, `subjectScope: "CASE"`
- [x] Add `job.lcaEtaCaseNumber`, `job.fullTime` (bool), `job.wageUnit`, `job.endDate`,
      `job.itineraryIncluded`, `job.offSiteWork`, `job.otherCompensation` (Part 5)
- [x] Add `petition.priorClassificationDenied` (BOOLEAN, reviewOnly, ATTORNEY) alongside
      existing `petition.priorClassificationGranted` (Part 4 Item 8a/8b split — **decided**)
- [x] Replace `signatory.fullName` with `signatory.lastName` + `signatory.firstName` (both
      TEXT, reviewOnly, ATTORNEY — **decided**, safe since Part 7 has zero existing mappings)
- [x] Add `signatory.phone`, `signatory.email` (TEXT, reviewOnly, ATTORNEY — Part 7 Item 3)
- [x] **H Classification Supplement fields** (owners decided — see inventory file table):
      `petition.h1bRegistrationConfirmationNumber` (ATTORNEY), `petition.beneficiaryControllingInterest`
      + `petition.beneficiaryControllingInterestExplanation` (ATTORNEY), `job.proposedDuties`
      (EMPLOYER), `beneficiary.workExperienceSummary` (BENEFICIARY). Passport number/country/
      expiry used at registration reuse the existing `beneficiary.passportNumber/passportCountry/
      passportExpiryDate` keys (mapped a second time in Session 2, not new questions).
- [x] **H-1B Data Collection Supplement fields** (owners decided):
      `employer.h1bDependentEmployer`, `employer.willfulViolatorHistory`, `employer.employs50PlusFlag`,
      `employer.over50PercentH1BL1Flag`, `employer.higherEdInstitution`, `employer.nonprofitAffiliatedHigherEd`,
      `employer.primarySecondaryEdInstitution`, `employer.nonprofitClinicalTraining` (all EMPLOYER);
      `petition.dolAttestationExempt` + `petition.dolExemptReason`, `petition.secondExtensionRequest`,
      `petition.amendedNoExtensionRequest`, `petition.correctingUscisError`, `petition.capType`,
      `petition.wageLevel`, `petition.capExemptReason` (all ATTORNEY — legal/compliance
      determinations); `beneficiary.highestEducationLevel`, `beneficiary.fieldOfStudy`,
      `beneficiary.mastersInstitutionName/DegreeAwardedDate/DegreeType/InstitutionAddress` (all
      BENEFICIARY); `job.annualRateOfPay`, `job.naicsCode`, `job.requiredEducationLevel`,
      `job.requiredFieldOfStudy`, `job.requiredYearsExperience`, `job.requiredSpecialSkills`,
      `job.supervisoryInfo` (all EMPLOYER — position requirements). Off-site assignment 3
      yes/no fields deferred (not added — still low priority, unchanged from the original plan).

**Storage decisions made while implementing** (not spelled out field-by-field in the original
checklist — recorded here so Session 2/3 don't have to re-derive them):
- All new per-petition facts (`job.*`, most new `petition.*`) use `storage: "generic"` with
  explicit `subjectScope: "CASE"` — required because the owner-based default (EMPLOYER→
  EMPLOYER_ORG, ATTORNEY→LAW_FIRM_ORG) would incorrectly share a value across every case for
  that org/firm.
- The 8 H-1B Data Collection **employer-characteristic** flags (`h1bDependentEmployer` …
  `nonprofitClinicalTraining`) intentionally use the **default** `EMPLOYER_ORG` scope — they
  describe the employer itself, not a specific petition.
- `beneficiary.workExperienceSummary` and the 6 new education fields
  (`highestEducationLevel`, `fieldOfStudy`, `masters*`) use the default `BENEFICIARY` scope —
  they describe the person, not a specific case.
- Only fields explicitly called out in the checklist above got new entity columns; every other
  new field is `storage: "generic"` per the config-only architecture rule.
- Test suite: 166/170 pass; ran `mvn test` after the change. The 4 remaining failures are in
  `FeatureEntitlementServiceTest` (missing `@Mock private UserFeatureRepository userFeatureRepo`)
  — a pre-existing gap unrelated to the immigration module, not touched by Session 1.

### Session 2 — I-129 PDF field mapping completeness (all parts + H supplements) — ✅ DONE 2026-07-02
- [x] Map Part 1 fields (Items 3-6: address province/country, email, nonprofit checkbox)
- [x] Map Part 2 fields (classificationSymbol/basisForClassification/requestedAction as
      checkboxes via `checkboxOnValue`, totalWorkers, mostRecentReceiptNumber)
- [x] Map Part 3 fields (provinceOfBirth, sevisNumber, sex as Male/Female checkbox pair)
- [x] Map Part 4 fields (6 reviewOnly booleans + granted/denied split)
- [x] Map Part 5 fields (LCA/ETA number, full-time, wage unit, end date, business
      type/year/employees/income)
- [x] Map Part 6 (EAR/ITAR) — 2 checkboxes
- [x] Map Part 7 fields (signatory last/first/title/signatureDate/phone/email)
- [x] Part 8 preparer — mapped the 2 fields that already have canonical questions
      (`attorney.firmName`→business name, `attorney.email`); `attorney.barNumber` has no
      Part 8 field on the real form (bar number isn't collected on I-129 itself), left unmapped
- [x] Map H Classification Supplement (registration confirmation number, controlling-interest
      Y/N + explanation, proposed duties, work-experience summary, passport reused a 2nd time,
      classification-sought checkbox reusing `petition.classificationSymbol`)
- [x] Map H-1B Data Collection Supplement (Section 1 employer/education/position fields,
      Section 2 ACWIA ✕ fee-exemption booleans, Section 3 cap type/wage level/master's-degree
      detail)

**What actually happened, for whoever resumes next:**
1. **The real PDF field names are nothing like the placeholder guesses.** The genuine
   USCIS PDF (`uploads/form-versions/I129/02-06-2026.pdf` — confirmed via PDF metadata:
   Author "USCIS - FMB", Title "Form I-129, Petition for Nonimmigrant Worker", edition text
   "02/27/26" inside the barcode fields) is an Adobe LiveCycle/XFA form with fully-qualified
   dotted field names like `form1[0].#subform[2].Line6_DateOfBirth[0]` — completely different
   from the `Pt3Line6_PassportNumber`-style names the old mapping file guessed. **Every single
   entry in the old `I129.json` was fictional and has been replaced.** There's also a second
   uploaded PDF (`04-02-2026.pdf`, FormVersion id 34, created via LibreOffice, 88 placeholder
   fields like `Text Box 1`/`beneficiary_firstName`) that looks like earlier scratch/test data
   for exercising the upload-mapping pipeline — left untouched, not the one now approved.
2. Field names were extracted with `pypdf` (`get_fields()` + walking `/Parent` chains for the
   fully-qualified name) since the project has no PDF field inspection tooling of its own.
   Each field's `/TU` tooltip contains the literal Part/Item instruction text, which is what
   made position-independent identification possible — **except** several fields share a
   copy-pasted tooltip (e.g. all 5 fields under the H-1B Data Collection Supplement's Item 7
   "position requirements" block have the identical wrong tooltip) — those were resolved by
   field *name* instead (`Line7_Education`, `Line8_FieldofStudy`, `Line9_YearsofExperience`,
   `Line10_SpecialSkills`, `Line4_BeneficiarySupervisePositionTitles`).
3. **New mapping file has 11 sections, 170 field entries, covering 106 question-key×field
   pairs** (many questions map to 2+ PDF fields — Yes/No booleans always need two checkbox
   entries, one per box). Verified two ways: (a) every `pdfFieldName` string checked
   programmatically against the real PDF's field list — zero mismatches; (b) uploaded via
   `POST /api/immigration/form-versions/33/upload-mapping`, which runs the app's own
   `CanonicalQuestionRegistry` validation (unknown-key check, repeat/checkbox-shape check) —
   passed clean, `fieldMappingVerified=true`. FormVersion id 33 (the real PDF) was then
   **approved** via `POST .../33/approve`, so it's now the active version PDF generation will
   use (`ImmPdfGenerationService` prefers a FormVersion's own `proposedMappingJson` over the
   classpath file — see decision log below).
4. **Two small `formsUsing` fixes were required for fields the real form actually has but the
   canonical question wasn't scoped to I129 for:** `beneficiary.alienNumber` and
   `beneficiary.eadCardNumber` both gained `"I129"` (same pattern as Session 1's gender/ssn
   fix). Without this, the mapping entries would be silently inert — the question would never
   be part of an I129 questionnaire/prefill, so no `FilingPackageAnswer` would ever exist for
   `ImmPdfGenerationService` to pull from.
5. Local backend was restarted (with permission) to pick up the `canonical-questions.json`
   edit before the upload-mapping validation could see the current question set — the running
   in-memory registry doesn't reload on file change.
6. Full test suite re-run after all edits: 170 tests, only the same pre-existing 5
   `FeatureEntitlementServiceTest` failures from before Session 1 (unrelated, not touched).
   `RepeatGroupTest`, `ReviewOnlyQuestionTest`, `DerivationRegistryTest`, `PdfFieldApplierTest`,
   `CanonicalAnswerServiceTest` all still green.

**Mapping decisions / judgment calls made (revisit if this bites someone later):**
- `petition.basisForClassification` (derived TEXT: "new employment"/"continuation"/"change of
  employer"/"amended petition") mapped to 4 of the real form's 6 checkboxes via
  `checkboxOnValue`. The other 2 real options ("change in previously approved employment",
  "new concurrent employment") have no corresponding `CaseType`/derivation value in this app —
  left unmapped, will just never be checked, which is correct (this app can't produce those
  case types).
- `petition.requestedAction` (derived: "notify office"/"extend"/"change status") — only mapped
  "notify office"→checkbox A and "extend"→checkbox C with confidence. **"change status" was
  deliberately left unmapped** — the real checkbox B ("change status and extend stay... now in
  the United States in another status") describes someone changing FROM a different
  nonimmigrant status, which doesn't clearly correspond to what `DerivationRegistry` produces
  "change status" for (H1B_TRANSFER = change of employer, not necessarily a status change).
  Given the UPL guardrail already in this codebase, mapping this without confidence risks
  silently checking the wrong box on a real government filing — flagged here rather than
  guessed. If someone wants this mapped, the fix belongs in `DerivationRegistry` (correct the
  derived value or add a distinct one), not in the mapping file.
- `petition.classificationSymbol` (derived "H-1B"/"H-4") is mapped **twice**: once as plain
  TEXT to Part 2 Item 1's write-in box, and again as a `checkbox` (`checkboxOnValue: "H-1B"`)
  to the H Classification Supplement's "Classification sought" checkbox (item 4, option A).
  This reuses the existing derivation instead of adding a new question, resolving the
  design question the inventory file raised ("may actually be the real backing data for this
  checkbox — needs a design decision"). For H4 cases this never checks (correct — H-4
  dependents don't have an H-1B classification-sought checkbox to check).
- `beneficiary.gender`, `petition.capType`, `petition.wageLevel`, `petition.dolExemptReason`,
  `beneficiary.highestEducationLevel` are all **free-TEXT** canonical questions being matched
  to real checkboxes via exact-string `checkboxOnValue`. This only works if the person typing
  the answer happens to type one of the expected strings (documented per-field below) — it is
  **not** a real dropdown/SELECT with enforced options. Expected values used:
  - `beneficiary.gender`: `"Male"` / `"Female"`
  - `petition.capType`: `"Bachelor's"` / `"Master's or higher"` / `"Chile/Singapore"` / `"Cap Exempt"`
  - `petition.wageLevel`: `"Level I"` / `"Level II"` / `"Level III"` / `"Level IV"`
  - `petition.dolExemptReason`: `"$60,000 salary exemption"` / `"master's degree exemption"`
    (matches the question's own `sublabel` examples)
  - `beneficiary.highestEducationLevel`: `"No Diploma"` / `"High School Diploma"` /
    `"Some College"` / `"College, No Degree"` / `"Associate's Degree"` / `"Bachelor's Degree"` /
    `"Master's Degree"` / `"Professional Degree"` / `"Doctorate Degree"`
  If this app ever adds a `SELECT` question type with enforced options (mentioned as a
  possibility elsewhere in the architecture notes), these 5 questions are the natural
  candidates to convert — would make the checkbox fill 100% reliable instead of string-luck.
- `petition.capExemptReason` (8-option ACWIA cap-exempt reason, free TEXT) — **left entirely
  unmapped**. Same free-text-to-checkbox problem as above but 8-way instead of 2-9, and the
  real option text is long/legalistic (not reducible to a short matchable phrase without
  inventing wording that isn't in the question's sublabel) — mapping this with invented
  strings felt like guessing at what the attorney meant rather than transcribing their answer.
- H-1B Data Collection Supplement Section 2 Item 3 ("nonprofit research org or governmental
  research org" — one of the 8 ACWIA fee-exemption booleans) has **no canonical question**.
  Session 1 explicitly deferred the full ACWIA fee-exemption breakdown as "a separate, much
  later concern" from the simpler Part 1 Item 6 boolean (`employer.nonprofitOrGovResearch`) —
  reusing that field here would contradict that decision, since Part 1 Item 6 and this ACWIA
  item are legally distinct questions. Left unmapped; add a dedicated question if ACWIA fee
  calculation ever becomes in-scope.
- `job.socCode` — **not mapped**. The real form only has a SOC code field on the H-1B Data
  Collection Supplement, split into two boxes (`Line5_SOCCode1` = first 2 digits,
  `Line5_SOCCode2` = last 4 digits). There's no split-string mapping mechanism in
  `PdfFieldApplier`/`FormFieldEntry` (mapping is 1 question-field → 1 PDF field, no substring
  extraction). Filling the whole "15-1252"-style value into one 2-character box would be
  actively wrong, so it's left blank rather than guessed. Would need a `PdfFieldApplier`
  enhancement (e.g. a `substring` transform) to do properly — Java change, out of this
  session's config-only scope.
- `beneficiary.mastersInstitutionAddress` — single free-TEXT question, but the real form
  breaks the address into street/apt/city/state/zip. Mapped to the street-number-and-name box
  only (best effort); city/state/zip are dropped. Same shape mismatch as `job.socCode` but
  lower stakes (an address is still mostly useful with just the street line filled).
- `beneficiary.priorStays` (LIST, `sourceList: priorVisasJson`, `maxRows: 4`, itemFields
  `visaType`/`country`/`issueDate`/`expiryDate`) vs. the real H Supplement Item 3 table (6 rows
  of Name + Period-of-Stay From/To, no visa-type or country columns): **shape mismatch**,
  because the real form's "Name" column is for *other* beneficiaries on a multi-beneficiary
  petition (this app is single-beneficiary only), not a property of one prior stay. Mapped
  `issueDate`→From and `expiryDate`→To for repeatIndex 0-3 only (our maxRows caps at 4 even
  though the real table has 6 rows); `visaType`/`country`/the `Name` column all left unfilled —
  there's no source data for "this beneficiary's own name, repeated per row" via the
  repeat-group value model (it assumes one row = one JSON array item, not a constant).
- `beneficiary.dependents` (LIST) has **no I-129 field at all** — the real form only asks
  Yes/No + a count for "filing applications for dependents" (Part 4 Item 5, `boolean` already
  mapped as `petition.dependentsFilingWithPetition`); actual dependent details belong on each
  dependent's own I-539, not the I-129. No mapping entries added for this LIST on I129.

**Real-form fields confirmed to NOT exist for canonical questions that claimed I129 in
`formsUsing`** (documentation only — did not change `formsUsing`, that's a Session 3 scoping
call, not this session's):
- `employer.website` — no website field anywhere on the real Part 1.
- `beneficiary.phone` — no beneficiary phone field on the real Part 3.
- `beneficiary.uscisAccountNumber` — no USCIS Online Account Number field on the real Part 3.

**Confirmed-real fields with NO canonical question yet (genuinely new, not just mapping gaps —
deferred, see "Session 2.5" below):** Part 1 Items 1/7/8 (individual petitioner name/IRS
number/SSN — low priority, app models company employers), Part 2 Item 3's "how many" counts
on Items 3/4/5/7, Part 4 Item 1 (consulate/POE notify block), Part 4 Item 10 (entertainment
group ≥1yr), Part 4 Item 11 (J-1 history), Part 4's beneficiary foreign-address block, Part 5
Item 3 (worksite address(es) + third-party-location), H Classification Supplement Items 1/2/6/7
(petitioner/beneficiary name repeat, total-beneficiaries count, Guam-CNMI cap exemption — all
niche/low priority), H-1B Data Collection Supplement Section 4 (off-site assignment, already
flagged deferred in Session 1), `petition.capExemptReason`'s 8 checkbox options (see above).

### Session 2.5 (optional, low priority — new questions, not just mapping)
Only worth doing if a real user need surfaces; none of these blocks Session 3.
- [ ] Add "how many" NUMBER companions for Part 4 Items 3/4/5/7 (currently boolean-only)
- [ ] Add Part 4 Item 1 consulate/POE notify fields + beneficiary foreign-address block
- [ ] Add Part 5 Item 3 worksite address(es) — candidate for a LIST question (2 real rows)
- [ ] Consider a `SELECT` question type with enforced options so the free-text→checkbox
      questions listed above (gender, capType, wageLevel, dolExemptReason,
      highestEducationLevel) stop depending on exact string luck
- [ ] Consider a `PdfFieldApplier` substring/split transform so `job.socCode` and similar
      split-across-two-boxes fields can be mapped properly instead of left blank

### Session 3 — Questionnaire scoping audit ("nothing else, nothing less") — ✅ DONE 2026-07-02
- [x] Add/extend a test: for a package with `selectedFormTypes=[X]`, the BENEFICIARY
      questionnaire spec == exactly `{q.key : q.owner=BENEFICIARY, X ∈ q.formsUsing}` — same
      assertion for EMPLOYER and ATTORNEY groups
- [x] Re-verify reviewOnly/derived questions still never leak into any questionnaire after
      Session 1/2 additions
- [x] Spot-check owner correctness on every field added in Sessions 1-2 (e.g. is
      `mostRecentReceiptNumber` really beneficiary-known, or attorney-only reviewOnly?)
- [x] Cleanup pass: consistent labels/sublabels, sensible `friendlySection` grouping with the
      new fields folded in

**What actually happened:**
1. The owner-grouping-minus-derived/reviewOnly filter that builds each questionnaire's spec
   previously lived only as an inline lambda inside `FilingPackageService.create()` — untestable
   without standing up the full service (7+ repo mocks, EmailService, PermissionService, etc.).
   Extracted it into a new pure method on the registry:
   `CanonicalQuestionRegistry.getQuestionnaireSpecByOwner(List<CanonicalQuestion>)` →
   `Map<owner, List<key>>`, built on the existing `getQuestionsByOwner()`. `create()` now calls
   this method instead of duplicating the filter — so the test below exercises the exact code
   path production uses, not a re-implementation of it.
2. New test file `src/test/java/.../immigration/service/QuestionnaireScopingTest.java` (6 tests,
   same no-Spring-context pattern as `ReviewOnlyQuestionTest`/`RepeatGroupTest` — constructs
   `CanonicalQuestionRegistry` directly against the real classpath `canonical-questions.json`):
   - `i129QuestionnaireSpecIsExactPerOwner` — the literal assertion this session's checklist
     asked for, for I129
   - `everyFormTagProducesExactSpecPerOwner` — same assertion swept across every form tag
     appearing anywhere in `formsUsing[]` (I129, I485, I140, PERM, DS160 today), so a future
     form gaining questions is covered automatically without a new test
   - `multiFormSelectionUnionsCorrectlyPerOwner` — a package selecting `[I129, I485]` at once
     must union both forms' questions per owner, not silently drop one
   - `noDerivedOrReviewOnlyQuestionEverAppearsInAnyQuestionnaireSpec` +
     `i129SpecOmitsKnownDerivedAndReviewOnlyKeys` — the Session 3 re-verification item, swept
     across all form tags plus an explicit key-name check for I129
   - `specKeysAreOwnedByExactlyOneOwnerAndUnique` — no duplicate keys within an owner's spec,
     no key claimed by more than one owner
   - All 6 pass against current `canonical-questions.json` (117 questions). One assertion bug
     found and fixed during the session: AssertJ's `doesNotContainAnyElementsOf` throws
     `IllegalArgumentException` if given an empty collection to check against — guarded with an
     early `continue` for form tags that have no derived/reviewOnly questions at all.
3. **Owner spot-check result: no discrepancies found.** Every field added in Sessions 1-2 was
   checked against its documented decision (the "Proposed owners" tables in
   `I129-FIELD-INVENTORY.md` and the Session 1 checklist's inline notes) — all 30 employer.*/
   petition.*/job.*/beneficiary.* additions have the owner they were assigned, and the
   reviewOnly flag is set on exactly the 14 attorney-attestation questions (6 Part 4 booleans +
   `priorClassificationDenied` + `exportControlLicenseRequired` + 6 signatory fields) and no
   others — matches `petition.h1bRegistrationConfirmationNumber` and the 8 H-1B DOL/cap
   `petition.*` fields correctly being plain ATTORNEY generic-storage (case facts the attorney
   enters), not reviewOnly attestations.
4. **Cleanup pass result: no defects found in the new fields** — labels are consistent Title
   Case, sublabels either explain the field or are intentionally blank matching the existing
   pattern for self-explanatory fields, and every new field's `friendlySection` groups sensibly
   (`employer.*` → `company_info`, `job.*` → `job_details`, new `petition.*` → `petition_info`,
   education fields → `education`, `workExperienceSummary` → its own `employment_history`).
   Two pre-existing (not Session 1-2) quirks noticed but **intentionally left alone** as
   out-of-scope for this session:
   - `employer.phone` resolves from `ImmOrg.contactName` (`DataResolver.java:162`, commented
     `// best available`) because `ImmOrg` has no phone column — a known, deliberately-chosen
     limitation predating this work, not a new regression.
   - The `notification_prefs` friendlySection has a label defined in
     `CanonicalQuestionRegistry.SECTION_LABELS` but zero questions use it — harmless dead
     config, predates Sessions 1-3.
5. Full suite: `mvn test` → 176 tests (170 + 6 new), same pre-existing 5
   `FeatureEntitlementServiceTest` failures as every prior session, unrelated to immigration.

### Session 4 — Repeat for I-765, I-131, I-539, G-28, I-290B, I-693
Recipe: add `formsUsing` tags + new `form-field-mappings/{Form}.json` + checklist template +
upload/approve real PDF. Do this per-form, one at a time.

#### Session 4a — I-765 (Application for Employment Authorization) — ✅ DONE 2026-07-02
User supplied the real current-edition PDF (`i-765_original.pdf`, Edition 08/21/25, Adobe
LiveCycle Designer 6.5 / XFA, 180 AcroForm fields, Author "USCIS"). Same methodology as Session 2:
extracted every field name + `/TU` tooltip via `pypdf`, matched by tooltip item-number text (not
by field name — see gotcha below), built the mapping, validated + approved through the app's own
endpoints.

**Catalog growth**: 117 → 136 canonical questions. 28 existing questions gained `"I765"` in
`formsUsing` (name/DOB/passport/I-94/alien number/SSN/gender/current-status/aliases/attorney.*
fields — all reused as-is, no shape changes). 19 brand-new questions added, **all `storage:
"generic"`, zero entity columns, zero DataResolver changes** — this session is pure config, no
Java model changes:
- BENEFICIARY (16): `eadApplicationReason`, `mailingInCareOfName`, `mailingAddressLine1/City/
  State/Zip`, `mailingSameAsPhysical`, `maritalStatus`, `priorI765Filed`, `cityOfBirth`,
  `travelDocumentNumber` (TEXT_SENSITIVE, encrypt=true — generic-store encryption works
  transparently, confirmed via `CanonicalAnswerService`), `statusAtLastArrival`, `email`,
  `applicationSignatureDate`, `petition.spouseH1bReceiptNumber`, `petition.i140ReceiptNumberForEad`
- ATTORNEY (3): `petition.g28Attached`, `petition.attorneyUscisOnlineAccountNumber`,
  `petition.eadEligibilityCategory`

**Ownership rationale (differs from I-129's pattern — worth flagging for whoever does I-131/
I-539 next)**: I-129 is filed *by* the employer/attorney *about* a beneficiary, so most fields
are EMPLOYER/ATTORNEY-owned. I-765 is filed *by* the beneficiary *about themselves* — nearly
every Part 1-3 field (name, address, eligibility history, contact info) is naturally
BENEFICIARY-owned, including two receipt-number fields (`spouseH1bReceiptNumber`,
`i140ReceiptNumberForEad`) that follow the same reasoning as I-129's already-decided
`petition.mostRecentReceiptNumber`: the beneficiary is the one holding the physical notice.
`eadEligibilityCategory` was kept ATTORNEY-owned despite that pattern because picking the
correct `(c)(##)` code requires knowing which USCIS eligibility category applies — a legal
determination, same reasoning as `petition.capType`/`wageLevel` on I-129 (UPL guardrail).

**Reused `beneficiary.address*`/`citizenshipCountry` for the imperfect-fit fields, same
judgment-call pattern as Session 2**:
- I-765 Part 2 Item 7 "U.S. Physical Address" reuses the existing `beneficiary.addressLine1/
  City/State/Zip` (already used as "current U.S. address" on I-129/I-485) rather than adding yet
  another address field — closest semantic match.
- I-765 Part 2 Item 5 "U.S. Mailing Address" (distinct from physical — allows an in-care-of name,
  can be an attorney's office) is genuinely new: `mailingInCareOfName`/`mailingAddressLine1/City/
  State/Zip`/`mailingSameAsPhysical`.
- I-765 Item 14.a/b (up to 2 countries of citizenship) — `beneficiary.citizenshipCountry` is a
  single TEXT field shared with I-129/I-485/I-140/PERM. Mapped only to 14.a; 14.b left unmapped
  (same shape-mismatch-not-guessed pattern as Session 2's masters-address partial mapping).
- `beneficiary.aliases` LIST `maxRows` bumped **2 → 3** (I-765 has 3 alias rows vs. I-129's 2) —
  safe, I-129's own mapping only ever used rows 0-1. Updated `RepeatGroupTest.
  listQuestionsLoadFromConfig`'s hardcoded `maxRows == 2` assertion to `3` (the only test
  touched this session; everything else — including `QuestionnaireScopingTest`'s dynamic
  form-tag sweep — needed zero changes since it discovers `formsUsing` tags at runtime).

**Field-naming gotcha (new instance of Session 2's "don't trust field names, trust `/TU`
tooltips" lesson)**: the PDF's "Other Names Used" alias rows are internally out of order — the
field literally named `Line3a_FamilyName[0]` has tooltip text "4. A." (item 4, i.e. logical row
2) while `Line3a_FamilyName[1]` has tooltip "3. A." (item 3, i.e. logical row 1). Mapped strictly
by tooltip item number, confirmed by reading each tooltip individually — repeatIndex 0→Item 2
fields, 1→Item 3 fields (the `[1]`-suffixed ones), 2→Item 4 fields (the `[0]`-suffixed ones).

**Deliberately unmapped/skipped, documented rather than guessed**:
- `petition.eadEligibilityCategory` (Item 27, the `(c)(##)` code) — the real PDF splits this
  into 3 separate boxes (`#area[1].section_1/2/3`, first-4/middle-3/last-3 characters). Same
  class of problem as Session 2's `job.socCode` — no substring-split transform exists in
  `PdfFieldApplier` (1 question-field → 1 PDF field only). Question added to the catalog (still
  useful data, shows in the questionnaire) but **left unmapped in `I765.json`** rather than
  guessing which single box to stuff the whole string into.
- STEM OPT sub-fields (Items 28.a-c: degree, E-Verify employer name/ID) — (c)(3)(C) is F-1/OPT
  specific; this app doesn't model F-1/OPT as a case type (H-1B + green card only per root
  CLAUDE.md). Skipped, matches the STEM-OPT-adjacent skip already made in Session 1 for SEVIS.
- Arrest/conviction questions (Items 30, 31.b — asylum (c)(8) and (c)(35)/(c)(36) history) —
  narrow, sensitive, and out of this app's H-1B/GC case-type scope. Skipped.
- Interpreter section (Part 4, entire) and most of the Preparer section (Part 5: preparer's own
  name/address/phone, statement checkboxes, signature) — boilerplate/attestation fields with no
  real case data behind them, same treatment as I-129's Part 8/9. Only `attorney.firmName`
  (→ preparer's Business/Org Name) and `attorney.email` were reused, matching exactly how I-129
  Session 2 handled its own Part 8 preparer section.
- Mobile phone, ABC settlement checkbox (Salvadoran/Guatemalan-specific), Apt/Ste/Flr address
  sub-fields — low-value secondary fields, consistent with what's already skipped elsewhere.

**Mapping file**: `form-field-mappings/I765.json`, 5 sections (`part1_reason`, `attorney_rep_
block`, `part2_applicant`, `part3_contact`, `part5_preparer`), 62 field entries. Every
`pdfFieldName` checked programmatically against the real PDF's field list — zero mismatches.
Uploaded the real PDF via `POST /api/immigration/form-versions` (multipart formType=I765,
editionDate=08/21/25, file) → created FormVersion id 65 → `POST .../65/upload-mapping` →
`fieldMappingVerified=true` on the first attempt (no validation errors) → `POST .../65/approve`
→ status APPROVED, now the active version for I-765 PDF generation. PDF persisted at
`uploads/form-versions/I765/08-21-25.pdf` by the app itself (no manual file copy needed).

**Checklist template**: added `i765Templates()` to `ChecklistTemplateSeeder` (8 items: passport
copy, 2×2 photos, I-94, prior EAD card copy, plus 4 condition-gated items — spouse's H-1B I-797 +
marriage certificate gated on `{"caseTypeIn":["H4_EAD"]}`, I-140 approval gated on
`{"i140Approved":true}`, I-485 receipt gated on `{"caseTypeIn":["GC_EAD"]}`). Note: the seeder
only runs `if (repo.count() > 0) return` at startup — a fresh/empty DB will pick these up
automatically, but this session's already-seeded local dev DB won't show them until reset; not
fixed here since changing that idempotency behavior is unrelated to this session's scope.

**Test suite**: 176 tests (same count — no new test files added, `QuestionnaireScopingTest`'s
dynamic sweep covers I765 automatically). One pre-existing test needed updating for the
legitimate `maxRows` change (`RepeatGroupTest`); the same 5 pre-existing unrelated
`FeatureEntitlementServiceTest` failures remain (missing `@Mock private UserFeatureRepository
userFeatureRepo`) — untouched by this or any prior session.

#### Session 4b — G-28 (Notice of Entry of Appearance as Attorney or Accredited Representative) — ✅ DONE 2026-07-02
User supplied the real current-edition PDF (`g-28_original.pdf`, Edition 09/17/18, Adobe
LiveCycle Designer 6.5 / XFA, 113 AcroForm fields, Author "USCIS"). Same methodology as 4a:
extracted every field name + `/TU` tooltip via `pypdf`, mapped strictly by tooltip text, built
the mapping, validated + approved through the app's own endpoints.

**Catalog growth**: 136 → 152 canonical questions. 19 existing questions gained `"G28"` in
`formsUsing` — notably `petition.attorneyUscisOnlineAccountNumber`, `beneficiary.mailingAddress*`,
`beneficiary.email`, and `beneficiary.applicationSignatureDate` (all four added just one session
earlier, in 4a, for I-765 — direct reuse with zero shape changes, a good sign the I-765 additions
were modeled at the right level of generality). 16 brand-new questions added, all ATTORNEY-owned,
all `storage: "generic"` with explicit `subjectScope: "CASE"` (a law firm can have more than one
attorney; case-scoping avoids the WRONG default LAW_FIRM_ORG-wide sharing that would otherwise
conflate different attorneys' names/addresses at the same firm): `attorney.lastName/firstName/
middleName`, `attorney.addressLine1/City/State/Zip/Province/PostalCode/Country`, `attorney.phone`,
`attorney.licensingAuthority`, `attorney.subjectToDisciplinaryOrder` (BOOLEAN), `attorney.
signatureDate` (DATE, **reviewOnly=true** — an attorney attestation/signature field, same
treatment as I-129's `signatory.*` block), `petition.g28MatterDescription`, `petition.
g28RequestedByRole`.

**Ownership pattern differs from both I-129 and I-765**: G-28 is filed *by* the attorney *about
themselves* (Parts 1-2) plus a short "who is my client" block (Part 3's "Information About
Client"). So nearly everything new is ATTORNEY-owned — the mirror image of I-765 being almost
entirely BENEFICIARY-owned. The "Information About Client" block reuses existing BENEFICIARY
fields (`lastName/firstName/middleName`, `alienNumber`, `uscisAccountNumber`, `phone`, `email`,
`mailingAddress*`) plus `employer.legalName` (for Item 7.a "Name of Entity" — G-28 can be filed
on behalf of either an individual client or a petitioning entity) and the existing reviewOnly
`signatory.title` (Item 7.b "Title of Authorized Signatory for Entity" — same underlying concept
as I-129's Part 7 signatory title).

**Field-naming gotcha — a new instance, this time a straight-up swap**: unlike 4a's
out-of-order-but-still-correct tooltips, this PDF has two fields whose *names* and *tooltips*
are simply swapped:
- Field named `Line6_EMail[0]` has tooltip "...**5. Enter Mobile Telephone Number**, if any."
- Field named `Line7_MobileTelephoneNumber[0]` has tooltip "...**6. Enter Email Address**, if any."

Mapped `attorney.email` → `Line7_MobileTelephoneNumber[0]` (the field whose *tooltip* says
Email, ignoring its misleading name) and skipped the actual mobile-phone field entirely (mobile
phone is consistently treated as low-value/skip across every session so far). Caught by reading
each tooltip individually rather than pattern-matching on field names — the specific check this
session needed since the name-vs-tooltip mismatch pattern from 4a recurred in a different shape.

**Deliberately unmapped/skipped, documented rather than guessed**:
- Part 2 eligibility-category checkboxes for "accredited representative of a nonprofit religious/
  charitable org" (Item 2.a-c), "associated with another attorney of record" (Item 3), and "law
  student/law graduate under supervision" (Item 4.a-b) — this app's `ATTORNEY` role assumes a
  licensed attorney (Item 1.a); the other three eligibility categories describe actor types this
  app doesn't model. Same reasoning as I-765's STEM-OPT skip: real fields the app's data model
  has no backing concept for.
- Part 3 Items 2.a-b (ICE) and 3.a-b (CBP) — "this appearance relates to matters before" ICE or
  CBP (immigration enforcement/border agencies). This app is a USCIS benefits-petition tracker,
  not an enforcement-defense tool; only the USCIS branch (Item 1.b, "list the form numbers") was
  modeled, as `petition.g28MatterDescription`.
- Part 4 Items 1.a-c (notice/secure-identity-document delivery preference checkboxes — "send to
  my attorney's business address instead of me") — a narrow administrative election, not case
  data; skipped as low-value, consistent with the pattern of skipping delivery/administrative
  preference checkboxes established in prior sessions.
- Part 5 Item 2.a-b (law student/law graduate's own signature + date) — out of scope since the
  law-student eligibility category itself was skipped above.
- Part 6 (Additional Information overflow sheet) — skipped, same as every prior session's Part 8/
  Part 9/Part 6 overflow treatment.
- Mobile phone (attorney's own, Item 5) and Fax Number (Item 7) — low-value secondary contact
  fields, consistent with mobile-phone skips elsewhere.
- Apt/Ste/Flr address sub-fields (both attorney's and client's address blocks) — consistent with
  every prior session's treatment of that sub-detail.

**No checklist template added** — and this was a deliberate decision, not an oversight. Unlike
I-129/I-485/I-140/PERM/I-765, G-28 is a procedural notice of appearance with no real supporting-
evidence requirement (no passport copies, degree certs, financial docs, etc. get attached to a
G-28 filing). Inventing checklist items just to match the "add a new form" recipe's usual shape
would be padding, not real evidence tracking — so `ChecklistTemplateSeeder` was left untouched
for G28.

**Mapping file**: `form-field-mappings/G28.json`, 5 sections (`part1_attorney`, `part3_appearance`,
`part3_client`, `part4_client_signature`, `part5_attorney_signature` — split by owner, unlike
I-765's owner-mixed sections, since G-28's attorney-vs-client split is much cleaner), 40 field
entries. Every `pdfFieldName` checked programmatically against the real PDF's field list — zero
mismatches. Uploaded the real PDF via `POST /api/immigration/form-versions` (multipart
formType=G28, editionDate=09/17/18, file) → created FormVersion id 97 → `POST .../97/upload-mapping`
→ `fieldMappingVerified=true` on the first attempt → `POST .../97/approve` → status APPROVED, now
the active version for G-28 PDF generation. PDF persisted at `uploads/form-versions/G28/09-17-18.pdf`
by the app itself.

**Test suite**: 176 tests (same count as 4a — no new test files needed; `QuestionnaireScopingTest`'s
dynamic sweep covers G28 automatically; no existing test needed updating this time, unlike 4a's
`maxRows` change). Only the same 5 pre-existing unrelated `FeatureEntitlementServiceTest`
failures remain.

#### Session 4c — I-131 (Application for Travel Documents, Parole Documents, and Arrival/Departure Records) — ✅ DONE 2026-07-02
User supplied the real current-edition PDF (`i-131_original.pdf`, Edition 01/20/25, Adobe
LiveCycle Designer 6.5 / XFA, 373 AcroForm fields per `pypdf` — the largest/most complex form
mapped so far, 14 pages). Same methodology as 4a/4b, plus a new **scoping-first** step this
session needed given the form's size.

**Scoping decision made before any field work** (the key judgment call this session): I-131
bundles at least 6 unrelated document types into one form — Reentry Permit, Refugee Travel
Document, TPS Travel Authorization, Advance Parole Document, Initial Parole/Re-parole/Parole in
Place (with sub-programs: Filipino WWII Veterans, Military PIP, CNMI, FRTF, IMMVI, CAM, Deferred
Enforced Departure, Intergovernmental Parole Referral, etc.). This app's `CaseType` enum only
models H-1B family + green card pathway + naturalization (per root CLAUDE.md) — it has no
refugee/asylee/TPS/parole/military case types at all. Rather than model all ~373 fields, **only
Part 1 Item 1 (Reentry Permit) and Item 5.A (Advance Parole based on a pending Form I-485)** were
modeled — the two categories with a real backing concept in this app (an LPR traveling, and an
I-485 applicant filing for Advance Parole, both squarely "green card pathway"). Every other
Part 1 category (Items 2-4, 6-13 and their dozens of sub-checkboxes) was skipped as out of scope,
same reasoning as G-28's ICE/CBP skip and I-129's non-H-1B-supplement skip — no guessing, just
"this app's data model has no concept for this," documented once at the top rather than
per-field. This cut the effective scope from 373 fields to the ~54 that matter.

**Catalog growth**: 152 → 161 canonical questions. 28 existing questions gained `"I131"` in
`formsUsing` — `beneficiary.mailingAddress*`/`applicationSignatureDate`/`email` (from 4a),
`petition.beneficiaryInRemovalProceedings` (from Session 1, reused for I-131 Part 4 Item 1's
"exclusion, deportation, removal, or rescission proceedings" question — same underlying concept,
slightly broader wording), plus the long-established `beneficiary.*` personal-info block
(name/aliases/address/A-number/DOB/citizenship/gender/SSN/USCIS account/I-94). 9 brand-new
questions, all BENEFICIARY-owned (this form is filed by/about the applicant, same pattern as
I-765), all `storage: "generic"`: `petition.i131ApplicationType` (Reentry Permit vs. Advance
Parole — the two in-scope categories), `petition.i485ReceiptNumberForAp`, `beneficiary.
classOfAdmission` (genuinely new — no existing COA field anywhere in the catalog despite it
appearing on multiple forms), `beneficiary.intendedDepartureDate/tripPurpose/tripCountries/
numberOfTrips/tripLengthDays` (Part 7, Advance Parole trip details — high value, directly
supports the concurrent-I-485-filing use case), `beneficiary.timeAbroadSincePermanentResidence`
(Part 5, Reentry Permit eligibility factor).

**Reuse win worth noting**: `beneficiary.classOfAdmission` was the only field on this form that
needed a genuinely new question — everything else in the in-scope Part 2 block already existed
from Sessions 1/2/4a, confirming those additions were modeled at the right level of generality
to serve a 4th and 5th form without modification.

**No name/tooltip swap gotcha this time** — the alias rows (`Part2_Line2_FamilyName1/2/3`) and
address blocks were internally consistent (field name suffix matched tooltip row/item number
exactly), unlike 4a and 4b. Still verified every mapping by reading tooltips individually per
established practice, just didn't find a mismatch to report.

**Mapping file**: `form-field-mappings/I131.json`, 7 sections (`part1_application_type`,
`part2_applicant`, `part4_processing`, `part5_reentry_permit`, `part7_travel`,
`part10_contact_signature`, `part12_preparer`), 54 field entries. Every `pdfFieldName` checked
programmatically against the real PDF's field list — zero mismatches. Uploaded via
`POST /api/immigration/form-versions` (multipart formType=I131, editionDate=01/20/25, file;
the app's own PDFBox-based extractor found 339 fields vs. `pypdf`'s 373 — a counting difference
between libraries, not a data problem, since every field this session's mapping references was
independently confirmed present by both) → created FormVersion id 129 → `POST .../129/upload-mapping`
→ `fieldMappingVerified=true` on the first attempt → `POST .../129/approve` → status APPROVED, now
the active version for I-131 PDF generation.

**Checklist template**: added `i131Templates()` to `ChecklistTemplateSeeder` (6 items) — unlike
G-28, I-131 has real supporting-evidence requirements for the two in-scope categories: passport
copy + 2×2 photos (universal), pending-I-485 receipt notice (gated `{"caseTypeIn":["I485"]}`),
green card copy (gated `{"caseTypeIn":["GC_RENEWAL"]}` — the closest existing `CaseType` for an
LPR renewing/traveling), prior travel-document copy and a travel-need explanation (both optional,
ungated).

**Test suite**: 176 tests (same count — no new test files needed, no existing test needed
updating this time). Only the same 5 pre-existing unrelated `FeatureEntitlementServiceTest`
failures remain.

#### Session 4d — I-539 (Application to Extend/Change Nonimmigrant Status) — ✅ DONE 2026-07-02
User supplied the real current-edition PDF (`i-539_original.pdf`, Edition 08/28/24, Adobe
LiveCycle Designer 6.5 / XFA, 176 AcroForm fields, 7 pages). Same methodology as 4a-4c. Unlike
I-131, **no scoping-first cut was needed** — I-539 is the form H-4 dependents actually use to
extend/change nonimmigrant status, which is a `CaseType` this app already models end-to-end
(`H4`), so essentially the entire form (minus the usual interpreter/overflow/preparer-detail
boilerplate) was in scope. This was the largest single-form catalog addition so far.

**Catalog growth**: 161 → 196 canonical questions (+35, the most of any Session 4 form). 38
existing questions gained `"I539"` in `formsUsing` — nearly the full personal-info/passport/
current-status block plus `petition.g28Attached`, `attorneyUscisOnlineAccountNumber`,
`mostRecentReceiptNumber`, and (notably) `petition.beneficiaryInRemovalProceedings` reused a
*third* time (I-129 → I-131 → I-539) for the same underlying "in removal proceedings" question
every one of these forms asks. 35 new questions:
- **18 BENEFICIARY-owned, plain generic** (not reviewOnly) — application type (Extension/
  ChangeOfStatus/Reinstatement), requested new status + effective date, filing scope (only
  applicant vs. with family) + total applicant count, requested-until date, whether based on a
  family member's already-granted extension, separate-filing status (3-way), related form type
  (I-539/I-129) + related beneficiary name + date filed, `beneficiary.grantedDurationOfStatus`
  (D/S flag — distinguishes fixed-expiry status from Duration-of-Status, high value for H-4
  tracking), and a 5-field foreign/physical-address-abroad block (genuinely new — no prior form
  had asked for an address *abroad*, only U.S. addresses).
- **17 ATTORNEY-owned, reviewOnly=true** — Part 4's full battery of sensitive background/
  eligibility attestations (immigrant-visa intent, immigrant petition ever filed, I-485 ever
  filed, arrests/convictions, 5 sub-items on torture/killing/injury/nonconsensual sexual contact/
  religious persecution, military-or-armed-group involvement, detention-facility involvement,
  weapon-threat group membership, weapons trafficking, weapons training, status violation,
  employment since last admission, J-1/J-2 history).

**Ownership judgment call for the 17 background questions** (the key decision this session):
I-539 is self-filed by the beneficiary (same pattern as I-765/I-131), so the "default" choice
established in those sessions would be plain BENEFICIARY-owned generic fields. Instead these 17
were modeled `reviewOnly: true`, ATTORNEY-owned — deliberately breaking from the I-765/I-131
default. Rationale: these are the *exact same class* of legally-sensitive compliance/criminal-
history/status-violation attestations I-129 already treats as reviewOnly (and Item 13, "removal
proceedings," is literally the same question I-129 already modeled that way — reused directly
rather than re-asked). Per the UPL guardrail already in this codebase, an attorney reviewing
"have you ever been involved in genocide" before it's baked into a federal filing is the safer
and more consistent choice than letting it flow straight from a self-service questionnaire —
matching how every other instance of this exact question category has been treated across every
prior session, rather than introducing a third ownership pattern. The 18 plain BENEFICIARY fields
above (filing logistics, related-form facts, D/S flag, foreign address) are procedural/factual,
not attestations, so they kept the I-765/I-131 default.

**Field-naming gotcha — another swap, smaller this time**: field `P4_Line1a_CountryOfIssuance[1]`
(Part 4 Item 1, "if your current passport information is different from Part 1") has tooltip
"...Enter **Passport Number**" despite its name saying `CountryOfIssuance`. Mapped by tooltip
(→ `beneficiary.passportNumber`, reused a second time for this form's "if different" duplicate
box — same harmless-duplicate-fill pattern as I-129's H-Supplement passport reuse), not by name.

**Reuse win**: of the 17 sensitive Part 4 background questions, only Item 13 (removal
proceedings) already existed — confirming that specific field's generality, but also showing
just how much *new* sensitive-attestation surface this one form introduced that no prior form
had asked about (torture/genocide, weapons trafficking, military service, etc. are all I-539-only
concerns in this app's forms so far).

**Mapping file**: `form-field-mappings/I539.json`, 9 sections (owner-split: `attorney_rep_block`,
`part1_applicant`, `part2_application_type`, `part3_processing`, `part4_additional_info`,
`part4_background_questions`, `part5_contact_signature`, `part7_preparer`), 102 field entries —
the largest mapping file so far. Every `pdfFieldName` checked programmatically against the real
PDF's field list — zero mismatches. Uploaded via `POST /api/immigration/form-versions` (multipart
formType=I539, editionDate=08/28/24, file) → created FormVersion id 161 (the app's own extractor
found 159 fields vs. `pypdf`'s 176 — same harmless library-counting difference noted in 4c) →
`POST .../161/upload-mapping` → `fieldMappingVerified=true` on the first attempt, despite this
being the largest/most complex mapping file yet → `POST .../161/approve` → status APPROVED, now
the active version for I-539 PDF generation.

**Checklist template**: added `i539Templates()` to `ChecklistTemplateSeeder` (6 items) — passport
copy + I-94 record (universal), principal H-1B holder's I-797 approval notice + marriage
certificate (both required, gated `{"caseTypeIn":["H4"]}` — the two pieces of evidence an H-4
dependent's extension is actually built on), plus two optional/ungated items (prior status
evidence, financial-support evidence — the latter directly mirrors the form's own instruction
text for Item 14's "No" branch).

**Test suite**: 176 tests (same count — no new test files needed). Full suite re-run despite the
large catalog addition (35 new questions, 17 of them reviewOnly) — `QuestionnaireScopingTest`'s
dynamic sweep and reviewOnly-exclusion checks passed with zero code changes, confirming the
registry's owner/reviewOnly filtering scales cleanly. Only the same 5 pre-existing unrelated
`FeatureEntitlementServiceTest` failures remain.

#### Session 4e — I-290B (Notice of Appeal or Motion) — ✅ DONE 2026-07-02
User supplied the real current-edition PDF (`i-290b_original.pdf`, Edition 05/31/24, Adobe
LiveCycle Designer 6.5 / XFA, 100 AcroForm fields, 5 pages). Same methodology as 4a-4d. Like
I-539 and unlike I-131, **no scoping-first cut was needed** — I-290B is a single coherent,
cross-cutting concept (appeal or reopen/reconsider any denied USCIS filing) rather than a bundle
of unrelated document types, so it applies uniformly across every `CaseType` this app already
models. Smallest of the five Session 4 forms so far.

**Catalog growth**: 196 → 202 canonical questions (+6, the smallest addition of any Session 4
form, proportional to the form's size). 22 existing questions gained `"I290B"` in `formsUsing` —
notably `petition.relatedFormType` (added in 4d for I-539's "which related form" checkbox pair)
reused a *second* time here as a **plain free-text field** (no `checkboxOnValue`) for Item 3's
"list only one form number" — same canonical question, different `pdfFieldType` per form, exactly
matching the documented design intent ("field-type metadata is per mapping entry, NOT per
question — same question can be text on one form, radio on another"). Also reused
`petition.mostRecentReceiptNumber` a fourth time (I-129 → I-131 → I-539 → I-290B) and
`employer.legalName` for Item 3's "Business or Organization Name" (mirrors G-28's reuse of the
same field for its own "Name of Entity" item — an appeal/motion filed by a company petitioner
uses the same field as everywhere else).

**Ownership decision**: unlike I-765/I-131/I-539 (self-filed, BENEFICIARY-owned procedural
facts), the 6 new questions here — brief status, motion type, requested classification, decision
date, issuing office, and the basis-for-appeal statement itself — were all modeled **ATTORNEY-
owned, plain generic (not reviewOnly)**. Rationale: I-290B is fundamentally a legal-strategy
document (what's the legal error, what's the argument, is a brief attached) that in practice an
attorney drafts, matching G-28's attorney-centric treatment rather than I-765/I-131/I-539's
beneficiary-centric one. This is the third distinct ownership pattern seen across Session 4
forms so far (beneficiary-self-filed procedural / attorney-owned attestation-reviewOnly /
attorney-owned-and-drafted-generic) — each matched to who actually holds the information and
drafts the content for that specific form, not applied mechanically from the previous session.

**Field-naming gotcha — a reordering, not a swap this time**: Part 2 Item 1's three appeal
brief-status checkboxes are internally out of sequence: `P2_Line1_checkbox[0]` = Item 1.a
("brief attached now"), `P2_Line1_checkbox[1]` = Item 1.**c** ("no brief"), `P2_Line1_checkbox[2]`
= Item 1.**b** ("will submit within 30 days") — the array index does not track the item letter.
Mapped strictly by tooltip text (1.a/1.b/1.c), not by array position — the motion-type checkboxes
just below it (`P2_Line2_checkbox[0-2]` → 2.a/2.b/2.c) were, by contrast, in correct sequence,
underscoring why every checkbox in every session gets its tooltip read individually rather than
assumed from a neighboring field's pattern.

**Mapping file**: `form-field-mappings/I290B.json`, 6 sections (`attorney_rep_block`,
`part1_applicant`, `part2_appeal_or_motion`, `part3_basis`, `part4_contact_signature`,
`part6_preparer`), 32 field entries — the smallest mapping file so far, proportional to the
form. Every `pdfFieldName` checked programmatically against the real PDF's field list — zero
mismatches. Uploaded via `POST /api/immigration/form-versions` (multipart formType=I290B,
editionDate=05/31/24, file) → created FormVersion id 193 (app's own extractor found 85 fields
vs. `pypdf`'s 100 — same harmless library-counting difference noted in 4c/4d) →
`POST .../193/upload-mapping` → `fieldMappingVerified=true` on the first attempt →
`POST .../193/approve` → status APPROVED, now the active version for I-290B PDF generation.

**Checklist template**: added `i290bTemplates()` to `ChecklistTemplateSeeder` (4 items, all
ungated) — copy of the unfavorable decision notice being appealed (required — this is the one
document every appeal/motion needs), brief/supporting evidence, new evidence for a motion to
reopen, and pertinent precedent decisions for a motion to reconsider (all three optional, since
which ones apply depends on `petition.motionType`/`appealBriefStatus` — facts the checklist
condition-rule engine can't currently branch on, same "leave conditionally-relevant items
optional rather than force a rule the engine doesn't support" choice made implicitly in prior
sessions).

**Test suite**: 176 tests (same count — no new test files needed, no existing test needed
updating). Only the same 5 pre-existing unrelated `FeatureEntitlementServiceTest` failures
remain.

### Session 4f — I-693 (Report of Immigration Medical Examination and Vaccination Record) — ✅ DONE 2026-07-03
User supplied the real current-edition PDF (`i-693_original.pdf`, Edition 01/20/25, Adobe
LiveCycle Designer 6.5 / XFA, 481 AcroForm fields, 14 pages — the largest field count of any
form in this whole effort). The 4c/4d/4e prediction held exactly: this needed the heaviest
scoping-first cut of any Session 4 form, cutting 481 fields down to a 27-entry mapping.

**Scoping decision** (made before touching individual fields, same discipline as 4c): I-693's
Parts 5-11 (pages 3-14, the bulk of the form) are a civil surgeon's clinical worksheet —
detailed TB/syphilis/gonorrhea lab results, Hansen's disease staging, DSM-based mental-health and
substance-use-disorder diagnoses, a per-vaccine grid with waiver codes, and a health-department
referral-evaluation section. None of this has — or should have — a home in this app's canonical
immigration-case profile: it's clinical/medical-record data, not case-tracking data, and storing
detailed diagnostic findings (drug-abuse diagnoses, STI test results) would be a meaningfully
different (and more sensitive) category of data than anything else this app holds, closer to
HIPAA-adjacent territory than immigration case facts. **Kept**: Part 1 (applicant identity/
address — same reusable `beneficiary.*` block as every other form), Part 2 (applicant contact +
signature), Part 4 (preparer, same 2-field `attorney.firmName`/`email` reuse as every other
form), and exactly two **high-level outcome summaries** — Part 6's overall Class A/B/none
finding + exam date, and Part 10's bottom-line vaccination-requirements result (met/waiver/not
met) — deliberately excluding the detailed clinical data feeding into those two summaries. Parts
3 (interpreter), 5 (ID document type — civil-surgeon paperwork, not case data), 7 (civil
surgeon's own identity/contact — no "civil surgeon" concept exists anywhere in this app's data
model, and creating one for a single niche data point would be over-engineering), 8 (the full
clinical worksheet), 9 (referral evaluation), and 11 (overflow) were all skipped as out of scope.

**Catalog growth**: 202 → 206 canonical questions (+4, tied with I-290B for the smallest addition
— proportionally the smallest relative to the source form's total size by a wide margin, exactly
as predicted). 18 existing questions gained `"I693"` in `formsUsing` — the by-now-standard
`beneficiary.*` identity/address block, `beneficiary.cityOfBirth` (added in 4d for I-539) reused
here for I-693's own "City/Town/Village of Birth" field, and the usual `attorney.firmName`/
`email` 2-field preparer reuse. 4 new questions, all BENEFICIARY-owned, CASE-scoped, deliberately
coarse-grained: `beneficiary.i693VaccinationOnlyEligible` (the Part 1 Item 4.A eligibility flag
for applicants who already completed an overseas exam), `beneficiary.medicalExamResult` (TEXT:
NoCondition/ClassB/ClassA — the Part 6 bottom line), `beneficiary.medicalExamDate`, and
`beneficiary.vaccinationRequirementsStatus` (TEXT: Completed/DoesNotMeet/WillRequestWaiver — the
Part 10 bottom line). No `reviewOnly` fields this session — unlike I-539's sensitive-attestation
battery, these 4 are neutral outcome facts, not legal attestations, so the ATTORNEY-reviewOnly
pattern established in 4d didn't apply here.

**Field-naming oddity, not quite a gotcha**: Part 1 Item 2 is labeled "Current Physical Address"
on the real form but its field set includes an "In Care Of Name" sub-field
(`Pt1Line2_StreetNumberName[1]`, tooltip confirms "Enter In Care of Name if any") — a field
normally associated with *mailing* addresses elsewhere in this app's forms (`beneficiary.
mailingInCareOfName`). Rather than force-fit this into the mailing-address canonical fields
(which represent a semantically different address on every other form), it was mapped to the
existing `beneficiary.addressLine1/City/State/Zip` (physical address) fields and the odd
in-care-of sub-field was left unmapped — consistent with the established "leave a genuine
shape/semantic mismatch documented and unmapped rather than force a fit" pattern (same reasoning
as Session 2's `job.socCode` and I-129's masters-address partial mapping).

**Mapping file**: `form-field-mappings/I693.json`, 5 sections (`part1_applicant`,
`part2_contact_signature`, `part4_preparer`, `part6_exam_summary`, `part10_vaccination_result`),
27 field entries — smallest mapping file of the six Session 4 forms, and by far the smallest
relative to its source form's total field count (27 of 481, roughly 5.6%, vs. I-131's 54 of 373
at ~14.5%, the next-most-scoped form). Every `pdfFieldName` checked programmatically against the
real PDF's field list — zero mismatches. Uploaded via `POST /api/immigration/form-versions`
(multipart formType=I693, editionDate=01/20/25, file) → created FormVersion id 225 (app's own
extractor found 446 fields vs. `pypdf`'s 481 — same harmless library-counting difference noted in
every prior session) → `POST .../225/upload-mapping` → `fieldMappingVerified=true` on the first
attempt → `POST .../225/approve` → status APPROVED, now the active version for I-693 PDF
generation.

**Checklist template**: added `i693Templates()` to `ChecklistTemplateSeeder` (3 items, all
ungated) — the completed/signed I-693 itself from the civil surgeon (required — this is the one
document that actually needs to be filed), plus two optional supporting items (prior vaccination
records, prior medical records) kept deliberately light given the scoped-down nature of this
session; no attempt to enumerate checklist items for the clinical detail that was intentionally
excluded from the data model.

**Test suite**: 176 tests (same count — no new test files needed, no existing test needed
updating). Only the same 5 pre-existing unrelated `FeatureEntitlementServiceTest` failures
remain, confirmed isolated through the final session of this effort.

## What's left (optional, not currently planned)
Every form originally listed for Session 4 (I-765, I-131, I-539, G-28, I-290B, I-693) is now
done. Nothing further is required to consider this multi-session effort complete. If resumed
later, natural candidates in rough priority order, none currently requested:
- **Session 2.5's deferred items** (still open, see that section above): NUMBER companions for
  I-129 Part 4 "how many" counts, Part 4 Item 1 consulate/POE notify fields, Part 5 Item 3
  worksite address(es) as a LIST, a `SELECT` question type for the free-text-to-checkbox fields
  flagged throughout (gender, capType, wageLevel, dolExemptReason, highestEducationLevel, and
  now I-131/I-539/I-693's similar TEXT-enum fields), and a `PdfFieldApplier` substring/split
  transform for `job.socCode`-class fields (also relevant to I-693's abandoned in-care-of
  sub-field and I-131's `eadEligibilityCategory` 3-box split, if those are ever revisited).
- **New forms beyond the original Session 4 list** — e.g. I-140 supplements, I-485 supplements,
  or DS-160 — would follow the same recipe: real PDF required, scope against `CaseType` first,
  reuse the by-now-large `beneficiary.*`/`attorney.*`/`employer.*`/`petition.*` catalog wherever
  possible before adding new questions.
- **I-693's civil-surgeon identity fields** (Part 7) were explicitly scoped out in 4f as
  low-value for this app's case-tracking purpose — revisit only if a real product need for
  tracking "which civil surgeon performed which exam" emerges (e.g., a directory feature), not
  as a default next step.

## Decisions (all resolved 2026-07-02 — Session 1 is unblocked)

1. **`petition.mostRecentReceiptNumber`** owner → **`BENEFICIARY`**. Rationale from user: the
   beneficiary is the party most likely to physically hold the prior notice; in the common
   case where the same attorney/firm handles an extension, the attorney will already know the
   value from their own case history and can supply/correct it via the existing generic
   attorney-override endpoint (`PUT …/packages/{packageId}/answers/{answerKey}` — works on any
   answer key, not just `reviewOnly` ones) — no new mechanism needed for that path.
2. **Non-H-1B supplements (E-1/E-2, Trade Agreement, L, O/P, Q-1, R-1)** → **confirmed out of
   scope**. This app's `CaseType` enum only models H-1B family + H-4 + green card pathway +
   naturalization. Do not add `formsUsing` tags or mappings for these classifications.
3. **H Classification Supplement + H-1B Data Collection Supplement** → **in scope, included in
   Sessions 1-2 below** (not deferred). Owners assigned per field — see the expanded checklist
   below and the "Proposed owners" table in `I129-FIELD-INVENTORY.md`.
4. **Schema-changing fields** → **both adapted**:
   - `petition.priorClassificationGranted` (existing) stays as-is (means "granted"); add a new
     sibling `petition.priorClassificationDenied` (BOOLEAN, reviewOnly, ATTORNEY) for Part 4
     Item 8b. Two real form checkboxes, two questions.
   - `signatory.fullName` (existing single TEXT) is **replaced** by `signatory.lastName` +
     `signatory.firstName` (both TEXT, reviewOnly, ATTORNEY) to match the real form's separate
     Family/Given fields. Safe to change now — Part 7 currently has zero PDF mappings, so
     there's no existing mapping to break.

## Token-saving notes for whoever resumes this
- Read this file first. The "Current state snapshot" and "Confirmed gaps" tables above are
  already-verified ground truth as of 2026-07-02 — don't re-run the grep/read sweeps that
  produced them unless you suspect the code has changed since.
- Update the checkboxes in this file directly as work completes — don't rely on conversation
  history surviving compaction.
- Each session should be self-contained: read this file, do the session's checklist, update
  checkboxes + snapshot facts that changed, stop.
