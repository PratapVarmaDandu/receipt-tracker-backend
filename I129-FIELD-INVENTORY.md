# I-129 Real-Form Field Inventory (Edition 02/27/26)

Source: actual current-edition I-129 PDF supplied by user 2026-07-02 (38 pages: base form
Parts 1-9 + 8 classification supplements + Attachment-1). This is the ground truth that
supersedes assumptions in `TODO-uscis-field-completeness.md`'s original "Confirmed gaps"
table — see corrections at the bottom.

**Scope decision needed**: this app models H-1B and green card cases (per root CLAUDE.md).
The base form (Parts 1-9) and two supplements — **H Classification Supplement** and
**H-1B/H-1B1 Data Collection and Filing Fee Exemption Supplement** — are relevant to every
H-1B case. The other six supplements (E-1/E-2, Trade Agreement, L, O/P, Q-1, R-1) cover visa
classifications (treaty trader/investor, intracompany transfer, extraordinary ability,
cultural exchange, religious worker) this app does not currently model as case types.
**Recommendation: skip those six for now**, revisit only if a non-H-1B classification becomes
a real case type. Flagged as an open question below.

Legend: 🆕 = no canonical question exists today · ✅ = maps to an existing question (may need
`formsUsing`/field-shape tweaks) · ⚠️ = existing question doesn't quite match the real field.

## Part 1. Petitioner Information
| Item | Field | Status | Notes |
|---|---|---|---|
| 1 | Individual Petitioner Family/Given/Middle Name | 🆕 | only relevant if petitioner is an individual, not a company — likely low priority, this app models company employers |
| 2 | Company or Organization Name | ✅ `employer.legalName` | |
| 3 | Mailing Address (In Care Of, Street, Apt/Ste/Flr, City, State, ZIP, **Province, Postal Code, Country**) | ⚠️ | `employer.addressLine1/city/state/zipCode` exist; **no Province/Country field** — confirms the earlier gap analysis |
| 4 | Daytime Phone, Mobile Phone, **Email Address** | ⚠️ | phone exists; **`employer.email` genuinely missing** (maps to unused `ImmOrg.contactEmail`) |
| 5 | Federal EIN | ✅ `employer.ein` | |
| 6 | "Are you a nonprofit organized as tax exempt or a governmental research organization?" Yes/No | 🆕 | **resolves open question #1** — it's a single boolean at this level, not the granular cap-exempt category set (that lives in the H-1B supplement's ACWIA section, a separate/later concern) |
| 7 | Individual IRS Tax Number | 🆕 | only relevant if petitioner is an individual — low priority |
| 8 | Petitioner's U.S. SSN (if individual) | 🆕 | low priority, same reason |

## Part 2. Information About This Petition
| Item | Field | Status | Notes |
|---|---|---|---|
| 1 | Requested Nonimmigrant Classification (write-in symbol) | ✅ `petition.classificationSymbol` (derived, TEXT) | matches — free text on the real form too |
| 2 | Basis for Classification — **6-option checkbox** (a. New employment … f. Amended petition) | ⚠️ | `petition.basisForClassification` exists as derived TEXT — fine for storage, but the PDF mapping must translate the derived string into one of the 6 exact checkbox labels via `checkboxOnValue`. `DerivationRegistry`'s output values need to exactly match those 6 strings (or a value map) for the mapping to work — a Session 2 mapping-layer concern, not a new question |
| 3 | "Provide the most recent petition/application receipt number for the beneficiary. If none exists, indicate 'None.'" | 🆕 | **resolves open question #2** — confirmed real field, TEXT |
| 4 | Requested Action — **6-option checkbox** (a-f, incl. free-trade options e/f) | ⚠️ | same pattern as Item 2 — `petition.requestedAction` derived TEXT needs checkbox valueMap at mapping time |
| 5 | Total number of workers | ✅ `petition.totalWorkers` | |

## Part 3. Beneficiary Information
| Item | Field | Status | Notes |
|---|---|---|---|
| 1 | Type of Beneficiaries: Named / Unnamed (H-2A/H-2B only) | 🆕 | niche (multi-beneficiary petitions) — low priority |
| 2 | Entertainment Group Name | 🆕 | niche — skip |
| 3 | Name of Beneficiary (Family/Given/Middle) | ✅ | |
| 4 | All other names used (aliases) | ✅ `beneficiary.aliases` LIST | |
| 5 | DOB, **Sex** (Male/Female checkbox), SSN, A-Number, Country of Birth, **Province of Birth**, Country of Citizenship | ⚠️🆕 | **Sex is a Male/Female checkbox pair, not free text** — existing `beneficiary.gender` is TEXT/formsUsing=I485-only; needs `formsUsing`+I129 added and a checkbox valueMap (Male→one box, Female→other) at mapping time. **Province of Birth genuinely exists on the real form (Part 3 Item 5) — corrects the earlier wrong assumption that it didn't.** `beneficiary.ssn` also needs I129 added to `formsUsing`. `beneficiary.provinceOfBirth` is a new question needed. |
| 6 | Date of Last Arrival, I-94 Number, Passport Number/Country/Issue/Expiry, Current Status, Status Expiry/D-S, **SEVIS Number**, EAD Number | ⚠️🆕 | most map to existing fields; **`beneficiary.sevisNumber` is new** (relevant to the earlier OPT/STEM-OPT/CPT conversation — this is the actual field that would carry a SEVIS record number) |
| 7 | Current Residential U.S. Address | ✅ | |

## Part 4. Processing Information
| Item | Field | Status | Notes |
|---|---|---|---|
| 1 | Consulate/PFI/POE notify — Type of Office, City, State/Country | 🆕 | new fields, moderate priority (only matters if beneficiary is abroad) |
| 2 | Valid passport for each beneficiary? Yes/No | ✅ `petition.beneficiaryHasValidPassport` | |
| 3 | Filing other petitions with this one? Yes(how many)/No | 🆕 | new |
| 4 | Filing replacement/initial I-94? Yes(how many)/No | ⚠️ | `petition.replacementI94Requested` exists as plain boolean; real form also wants a "how many" count — minor field addition |
| 5 | Filing for dependents? Yes(how many)/No | ⚠️ | `petition.dependentsFilingWithPetition` exists as boolean; real form also wants a count |
| 6 | Any beneficiary in removal proceedings? | ✅ `petition.beneficiaryInRemovalProceedings` | |
| 7 | Ever filed an immigrant petition for any beneficiary? Yes(how many)/No | 🆕 | new — distinct from the nonimmigrant-petition question below |
| 8 | New petition per Part 2? → 8a. classification granted in last 7yrs? 8b. classification denied in last 7yrs? | ⚠️ | `petition.priorClassificationGranted` exists as ONE boolean — **real form splits granted vs. denied into two separate questions (8a/8b)** — needs a second question added (`petition.priorClassificationDenied`) |
| 9 | Ever previously filed a nonimmigrant petition for this beneficiary? | ✅ `petition.priorPetitionsFiledForBeneficiary` | distinct from Part 2 Item 3's receipt-number field — this is the yes/no, that's the number |
| 10 | Entertainment group ≥1 year? | 🆕 | niche — skip |
| 11a/b | Ever J-1 exchange visitor/J-2 dependent? + dates | 🆕 | new, low-moderate priority |
| — | Beneficiary's Foreign Address (Street/Apt/City/State-Province/Postal/Country) | 🆕 | new address block |

## Part 5. Basic Information About Proposed Employment and Employer
| Item | Field | Status | Notes |
|---|---|---|---|
| 1 | Job Title | ✅ `job.title` | |
| 2 | LCA/ETA Case Number | 🆕 | **notably missing** — this is a core H-1B compliance field, should be high priority |
| 3 | Worksite address(es) (up to 2, each w/ third-party-location Y/N + org name) | 🆕 | new — could model as a 2-row LIST or two discrete address blocks |
| 4 | Itinerary included? | 🆕 | new |
| 5 | Off-site work? | 🆕 | new |
| 6 | CNMI exclusive work? | 🆕 | niche |
| 7 | Full-time position? | 🆕 | new |
| 8 | Hours/week if not full-time | ✅ `job.hoursPerWeek` | already exists, good |
| 9 | Wages $ + per (hour/week/month/year) | ⚠️ | `job.salaryAmount` exists; **missing the "per" unit** (`job.wageUnit`) |
| 10 | Other Compensation (explain) | 🆕 | new |
| 11 | Employment dates From/To | ⚠️ | `job.startDate` exists (From); **`job.endDate` (To) is missing** |
| 12 | Type of Business | 🆕 | new (`employer.businessType`) |
| 13 | Year Established | 🆕 | new (`employer.yearEstablished`) |
| 14 | Current # employees in US | 🆕 | new (`employer.employeeCount`) |
| 15 | ≤25 FTE employees total? | 🆕 | new (`employer.smallEmployerFlag`) — feeds ACWIA fee logic too |
| 16 | Gross Annual Income | 🆕 | new (`employer.grossAnnualIncome`) |
| 17 | Net Annual Income | 🆕 | new (`employer.netAnnualIncome`) |

## Part 6. Certification Regarding Release of Controlled Technology (EAR/ITAR)
Required only for H-1B, H-1B1 Chile/Singapore, L-1, O-1A. Two mutually-exclusive statements
(license not required / license required + will prevent access). Matches
`petition.exportControlLicenseRequired` (BOOLEAN, reviewOnly) — **design already anticipates
this** ("checkboxOnValue lets one Yes/No question map to two checkbox fields" per the Phase 4
notes). No new question needed, just the Session 2 PDF mapping.

## Part 7. Declaration, Signature, Contact Info of Petitioner/Authorized Signatory
| Item | Field | Status | Notes |
|---|---|---|---|
| 1 | Name and Title of Authorized Signatory: **Family Name + Given Name separately** + Title | ⚠️ | `signatory.fullName` exists as ONE text field (reviewOnly) — **real form wants Family/Given split**. Either split the canonical question into `signatory.lastName`/`signatory.firstName`, or keep `fullName` and split programmatically at PDF-fill time (simpler, no schema change, but fragile for non-Western name order) — flagging as a decision point |
| 2 | Signature + Date of Signature | ✅ `signatory.signatureDate` | signature itself isn't data-driven (wet/electronic sig placeholder) |
| 3 | Signatory's Daytime Phone + Email | 🆕 | new (`signatory.phone`, `signatory.email`) |

## Part 8. Declaration/Signature/Contact of Preparer (if other than Petitioner)
Entirely unmodeled today: Preparer Family/Given Name, Business/Org Name, Mailing Address
(Street/Apt/City/State/ZIP/Province/Postal/Country), Contact (Phone/Fax/Email), Signature+Date.
**Conditional section** — only needed when someone other than the petitioner/attorney prepared
the form. Lower priority; likely `owner: ATTORNEY`, several fields, or skip until a real need
arises (attorney is usually the preparer already captured via `attorney.*`).

## Part 9. Additional Information
Free-form continuation sheet (A-Number + repeatable Page/Part/Item Number + explanation text).
This is an escape hatch for overflow explanations, not structured data — **not worth modeling
as individual canonical questions**. Recommend skipping entirely (no `formsUsing` questions).

## H Classification Supplement (required for every H filing — directly relevant)
| Field | Status | Notes |
|---|---|---|
| Total number of beneficiaries (if unnamed) | 🆕 | low priority given app is single-beneficiary-per-case |
| Prior periods of stay in H/L classification (table: name, from, to) | ✅-ish | shape matches `beneficiary.priorStays` LIST — verify itemFields align (name/from/to) |
| Classification sought (8-option select: H-1B, H-1B1, H-1B2 DOD, H-1B3 fashion model, H-2A, H-2B, H-3, H-3 special ed) | 🆕 | this may actually be the real backing data for `petition.classificationSymbol`/`basisForClassification` derivation rather than a separate field — needs a design decision, not a blind add |
| **H-1B Registration Confirmation Number** (cap petitions) | 🆕 | **high priority** — this is the H-1B lottery confirmation number, a very commonly needed field |
| Passport number/country/expiry used at registration | 🆕 | new |
| Guam-CNMI cap exemption questions | 🆕 | niche, low priority |
| Beneficiary has controlling interest in petitioner? + explain | 🆕 | new, relevant to H-1B employer-employee relationship scrutiny |
| Describe proposed duties | 🆕 | **high value** — commonly needed for H-1B specialty-occupation evidence |
| Describe beneficiary's present occupation + prior work summary | 🆕 | high value |
| H-2A/H-2B/H-3 sections (Sections 2-3) | — | out of scope, not H-1B |

## H-1B and H-1B1 Data Collection and Filing Fee Exemption Supplement (required for every H-1B)
| Field | Status | Notes |
|---|---|---|
| Employer info checkboxes (H-1B dependent employer? willful violator? beneficiary DOL-attestation-exempt + why? employs 50+? >50% in H/L status?) | 🆕 | new — moderately important for H-1B dependent-employer compliance |
| Beneficiary's Highest Level of Education (9-option select) | 🆕 | new, high value |
| Major/Primary Field of Study | 🆕 | new, high value |
| Rate of Pay Per Year | 🆕 | new (distinct from `job.salaryAmount`/Part 5 wages — this supplement wants annualized rate) |
| SOC Code | ✅ `job.socCode` | |
| NAICS Code | 🆕 | new |
| Position requirements (education level, field of study, years experience, special skills, supervisory info) | 🆕 | new, valuable for RFE-avoidance documentation |
| ACWIA fee exemption questions (8 yes/no: higher-ed institution, nonprofit affiliated with higher ed, nonprofit/govt research org, 2nd+ extension, amended-no-extension, correcting USCIS error, primary/secondary ed institution, nonprofit clinical training) | 🆕 | **this is the full cap-exempt/fee-exemption category set** — more granular than Part 1 Item 6's single boolean. Relevant only if this app ever needs to calculate/display the ACWIA fee amount — **recommend deferring**, not core case data |
| Numerical Limitation: cap type (Bachelor's/Master's+/Chile-Singapore/Cap Exempt), wage level (I-IV), cap-exempt reason (8 options), master's degree institution details | 🆕 | **high value for H-1B cap cases** — `petition.capType`, `petition.wageLevel`, `petition.capExemptReason` |
| Off-site assignment (3 yes/no) | 🆕 | low priority |

## Supplements recommended to skip for now (visa classifications this app doesn't model)
E-1/E-2 (treaty trader/investor), Trade Agreement (TN/H-1B1 free trade), L Classification
(intracompany transfer), O and P (extraordinary ability / athletes/entertainers), Q-1
(cultural exchange), R-1 (religious worker), Attachment-1 (multi-beneficiary petitions).

## Decisions (2026-07-02 — all open questions resolved, see TODO.md "Decisions")
- `petition.mostRecentReceiptNumber` → owner **BENEFICIARY** (attorney can still correct via
  the existing generic override endpoint when they know it from prior filings)
- E-1/E-2, Trade Agreement, L, O/P, Q-1, R-1 supplements → **out of scope**, not modeled
- H Classification Supplement + H-1B Data Collection Supplement → **in scope**, owners below
- `priorClassificationGranted`/`Denied` → split into two questions (both ATTORNEY, reviewOnly)
- `signatory.fullName` → replaced by `signatory.lastName` + `signatory.firstName`

## Proposed owners — H Classification Supplement (new fields)
| Proposed key | Type | Owner | Notes |
|---|---|---|---|
| `petition.h1bRegistrationConfirmationNumber` | TEXT | ATTORNEY | attorney typically manages the H-1B registration/lottery process |
| `petition.beneficiaryControllingInterest` | BOOLEAN | ATTORNEY | compliance/legal determination, same pattern as other `petition.*` attorney fields |
| `petition.beneficiaryControllingInterestExplanation` | TEXT | ATTORNEY | only needed if the above is true |
| `job.proposedDuties` | TEXT | EMPLOYER | job description, same owner as `job.title` |
| `beneficiary.workExperienceSummary` | TEXT | BENEFICIARY | describes the beneficiary's own occupation/work history |
| passport number/country/expiry at registration | — | (reuse) | maps to existing `beneficiary.passportNumber/passportCountry/passportExpiryDate` a second time in the mapping file — not new questions |

## Proposed owners — H-1B and H-1B1 Data Collection and Filing Fee Exemption Supplement (new fields)
| Proposed key | Type | Owner | Notes |
|---|---|---|---|
| `employer.h1bDependentEmployer` | BOOLEAN | EMPLOYER | employer characteristic |
| `employer.willfulViolatorHistory` | BOOLEAN | EMPLOYER | employer characteristic |
| `employer.employs50PlusFlag` | BOOLEAN | EMPLOYER | employer characteristic |
| `employer.over50PercentH1BL1Flag` | BOOLEAN | EMPLOYER | employer characteristic |
| `employer.higherEdInstitution` | BOOLEAN | EMPLOYER | ACWIA fee exemption category |
| `employer.nonprofitAffiliatedHigherEd` | BOOLEAN | EMPLOYER | ACWIA fee exemption category |
| `employer.primarySecondaryEdInstitution` | BOOLEAN | EMPLOYER | ACWIA fee exemption category |
| `employer.nonprofitClinicalTraining` | BOOLEAN | EMPLOYER | ACWIA fee exemption category |
| `petition.dolAttestationExempt` | BOOLEAN | ATTORNEY | legal/compliance determination |
| `petition.dolExemptReason` | TEXT/enum | ATTORNEY | "$60k salary" vs. "master's+ degree" |
| `petition.secondExtensionRequest` | BOOLEAN | ATTORNEY | case-history fact, attorney-tracked |
| `petition.amendedNoExtensionRequest` | BOOLEAN | ATTORNEY | case-history fact |
| `petition.correctingUscisError` | BOOLEAN | ATTORNEY | case-history fact |
| `petition.capType` | TEXT/enum | ATTORNEY | legal classification (Bachelor's/Master's+/Chile-Singapore/Cap Exempt) |
| `petition.wageLevel` | TEXT/enum | ATTORNEY | legal classification (I-IV) |
| `petition.capExemptReason` | TEXT/enum | ATTORNEY | legal classification (8 options) |
| `beneficiary.highestEducationLevel` | TEXT/enum | BENEFICIARY | own credentials |
| `beneficiary.fieldOfStudy` | TEXT | BENEFICIARY | own credentials |
| `beneficiary.mastersInstitutionName` / `mastersDegreeAwardedDate` / `mastersDegreeType` / `mastersInstitutionAddress` | TEXT/DATE | BENEFICIARY | own credentials, only needed if `capType` = Master's+ |
| `job.annualRateOfPay` | NUMBER | EMPLOYER | distinct from Part 5's `job.salaryAmount`+unit — this one is always annualized |
| `job.naicsCode` | TEXT | EMPLOYER | position/employer classification |
| `job.requiredEducationLevel` / `requiredFieldOfStudy` / `requiredYearsExperience` / `requiredSpecialSkills` / `supervisoryInfo` | TEXT/NUMBER | EMPLOYER | position requirements, same owner as `job.title` |
| Off-site assignment (3 yes/no) | BOOLEAN | EMPLOYER | low priority, can defer |

## Corrections to `TODO-uscis-field-completeness.md`
1. **Province of Birth**: the original plan said "confirmed not present on real I-129 Part 3" —
   **this was wrong**. It IS present (Part 3, Item 5). Add `beneficiary.provinceOfBirth`.
2. **Open question #1 (profit/non-profit)**: resolved — Part 1 Item 6 is one simple boolean at
   the base-form level. The full H-1B cap-exempt category breakdown is a separate, much later
   concern (ACWIA fee supplement), not needed for base filing data.
3. **Open question #2 (mostRecentReceiptNumber owner)**: still open, but now grounded — it's
   Part 2 Item 3, sitting in the petition-info section alongside other attorney-owned
   `petition.*` fields. Leaning ATTORNEY to match the pattern of its neighbors, pending
   confirmation.
4. **Open question #4 (real PDF)**: resolved — provided 2026-07-02.
5. **New open question #5**: should the 6 non-H-1B classification supplements (E/Trade
   Agreement/L/O-P/Q-1/R-1) be modeled at all, given this app's case model is H-1B/GC only?
   Recommend explicitly marking out-of-scope rather than silently skipping.
6. **New open question #6**: several real fields split what's currently a single derived/
   reviewOnly question into two real ones (`priorClassificationGranted` vs. `...Denied`;
   `signatory.fullName` vs. separate last/first name) — these are schema decisions, not just
   config additions, worth confirming before Session 1 touches them.
