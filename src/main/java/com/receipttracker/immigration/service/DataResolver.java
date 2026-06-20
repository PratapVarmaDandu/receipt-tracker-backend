package com.receipttracker.immigration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.receipttracker.immigration.model.*;
import com.receipttracker.immigration.model.question.CanonicalQuestion;
import com.receipttracker.immigration.model.question.ResolvedValue;
import com.receipttracker.service.EncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * Resolves prefill values for canonical questions from in-memory data objects.
 *
 * <p>Resolution context is explicit — no database calls in this class.
 * Callers (e.g. FormPacketService) are responsible for loading the necessary
 * entities before calling resolve().</p>
 *
 * <p>Encrypted fields are decrypted here and returned as plaintext so callers
 * (PDF filler, data-collection prefill API) always receive usable values.</p>
 */
@Service
public class DataResolver {

    private static final Logger log = LoggerFactory.getLogger(DataResolver.class);

    @Autowired private EncryptionService encryptionService;
    @Autowired private ObjectMapper objectMapper;

    // ── Context container ─────────────────────────────────────────────────────

    /**
     * All canonical data objects needed for resolution.
     * Null fields are fine — DataResolver returns ResolvedValue.none() gracefully.
     */
    public record ResolutionContext(
            CanonicalProfile profile,
            ImmOrg employerOrg,
            ImmOrg lawFirmOrg,
            ImmigrationCase immigrationCase,
            AttorneyProfile attorneyProfile
    ) {
        public static ResolutionContext of(CanonicalProfile profile, ImmOrg employerOrg,
                                           ImmOrg lawFirmOrg, ImmigrationCase c,
                                           AttorneyProfile attorneyProfile) {
            return new ResolutionContext(profile, employerOrg, lawFirmOrg, c, attorneyProfile);
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Resolve a single question's prefill value.
     */
    public ResolvedValue resolve(CanonicalQuestion question, ResolutionContext ctx) {
        if (question == null || question.getKey() == null) return ResolvedValue.none();
        try {
            return dispatchByKey(question.getKey(), ctx);
        } catch (Exception e) {
            log.warn("DataResolver failed for key '{}': {}", question.getKey(), e.getMessage());
            return ResolvedValue.none();
        }
    }

    /**
     * Resolve all questions in one pass; returns map of key → ResolvedValue.
     * Never throws — each key resolves independently.
     */
    public Map<String, ResolvedValue> resolveAll(List<CanonicalQuestion> questions, ResolutionContext ctx) {
        Map<String, ResolvedValue> result = new LinkedHashMap<>();
        for (CanonicalQuestion q : questions) {
            result.put(q.getKey(), resolve(q, ctx));
        }
        return result;
    }

    // ── Dispatch ──────────────────────────────────────────────────────────────

    private ResolvedValue dispatchByKey(String key, ResolutionContext ctx) {
        return switch (key) {

            // ── beneficiary.personal_info ─────────────────────────────────────
            case "beneficiary.lastName"          -> profileStr(ctx, p -> p.getLegalLastName());
            case "beneficiary.firstName"         -> profileStr(ctx, p -> p.getLegalFirstName());
            case "beneficiary.middleName"        -> profileStr(ctx, p -> p.getMiddleName());
            case "beneficiary.dateOfBirth"       -> profileDate(ctx, p -> p.getDateOfBirth());
            case "beneficiary.countryOfBirth"    -> profileStr(ctx, p -> p.getCountryOfBirth());
            case "beneficiary.citizenshipCountry"-> profileStr(ctx, p -> p.getCitizenshipCountry());
            case "beneficiary.gender"            -> profileStr(ctx, p -> p.getGender());
            case "beneficiary.phone"             -> profileStr(ctx, p -> p.getPhone());
            case "beneficiary.uscisAccountNumber"-> profileStr(ctx, p -> p.getUscisOnlineAccountNumber());
            case "beneficiary.addressLine1"      -> addressField(ctx, "line1");
            case "beneficiary.addressCity"       -> addressField(ctx, "city");
            case "beneficiary.addressState"      -> addressField(ctx, "state");
            case "beneficiary.addressZip"        -> addressField(ctx, "zip");

            // ── beneficiary.passport_id ───────────────────────────────────────
            case "beneficiary.passportNumber"    -> profileEncrypted(ctx, p -> p.getPassportNumberEnc());
            case "beneficiary.passportCountry"   -> profileStr(ctx, p -> p.getPassportCountry());
            case "beneficiary.passportIssueDate" -> profileDate(ctx, p -> p.getPassportIssueDate());
            case "beneficiary.passportExpiryDate"-> profileDate(ctx, p -> p.getPassportExpiryDate());
            case "beneficiary.i94Number"         -> resolveI94(ctx);
            case "beneficiary.portOfEntry"       -> profileStr(ctx, p -> p.getPortOfEntry());
            case "beneficiary.entryDate"         -> profileDate(ctx, p -> p.getEntryDate());
            case "beneficiary.alienNumber"       -> profileEncrypted(ctx, p -> p.getAlienNumberEnc());
            case "beneficiary.ssn"               -> profileEncrypted(ctx, p -> p.getSsnEnc());

            // ── beneficiary.current_status ────────────────────────────────────
            case "beneficiary.currentVisaType"   -> profileStr(ctx, p -> p.getCurrentVisaType());
            case "beneficiary.currentVisaExpiry" -> profileDate(ctx, p -> p.getCurrentVisaExpiry());

            // ── beneficiary.ead_info ──────────────────────────────────────────
            case "beneficiary.eadCardNumber"     -> profileEncrypted(ctx, p -> p.getEadCardNumberEnc());
            case "beneficiary.eadCategory"       -> profileStr(ctx, p -> p.getEadCategory());
            case "beneficiary.eadExpiryDate"     -> profileDate(ctx, p -> p.getEadExpiryDate());
            case "beneficiary.eadCaseNumber"     -> profileStr(ctx, p -> p.getEadCaseNumber());

            // ── employer.company_info ─────────────────────────────────────────
            case "employer.legalName"     -> orgStr(ctx.employerOrg(), o -> o.getName());
            case "employer.ein"           -> orgStr(ctx.employerOrg(), o -> o.getEinNumber());
            case "employer.addressLine1"  -> orgStr(ctx.employerOrg(), o -> o.getAddress());
            case "employer.city"          -> orgStr(ctx.employerOrg(), o -> o.getCity());
            case "employer.state"         -> orgStr(ctx.employerOrg(), o -> o.getStateCode());
            case "employer.zipCode"       -> orgStr(ctx.employerOrg(), o -> o.getZipCode());
            case "employer.phone"         -> orgStr(ctx.employerOrg(), o -> o.getContactName()); // best available
            case "employer.website"       -> orgStr(ctx.employerOrg(), o -> o.getWebsite());

            // ── job.job_details — sourced from most recent employment entry ───
            case "job.title"        -> employmentField(ctx, "title");
            case "job.startDate"    -> employmentField(ctx, "startDate");
            case "job.socCode"      -> ResolvedValue.none();  // no backing field yet
            case "job.salaryAmount" -> ResolvedValue.none();  // no backing field yet
            case "job.hoursPerWeek" -> ResolvedValue.none();  // no backing field yet

            // ── attorney.* ────────────────────────────────────────────────────
            case "attorney.firmName" -> orgStr(ctx.lawFirmOrg(), o -> o.getName());
            case "attorney.email"    -> orgStr(ctx.lawFirmOrg(), o -> o.getContactEmail());
            case "attorney.barNumber"-> resolveBarNumber(ctx);

            default -> {
                log.debug("No resolver for key '{}' — returning none", key);
                yield ResolvedValue.none();
            }
        };
    }

    // ── Profile helpers ───────────────────────────────────────────────────────

    @FunctionalInterface
    private interface ProfileExtractor<T> { T extract(CanonicalProfile p); }

    private ResolvedValue profileStr(ResolutionContext ctx, ProfileExtractor<String> fn) {
        if (ctx.profile() == null) return ResolvedValue.none();
        String val = fn.extract(ctx.profile());
        return blank(val) ? ResolvedValue.none() : ResolvedValue.fromProfile(val);
    }

    private ResolvedValue profileDate(ResolutionContext ctx, ProfileExtractor<LocalDate> fn) {
        if (ctx.profile() == null) return ResolvedValue.none();
        LocalDate d = fn.extract(ctx.profile());
        return d == null ? ResolvedValue.none() : ResolvedValue.fromProfile(d.toString());
    }

    private ResolvedValue profileEncrypted(ResolutionContext ctx, ProfileExtractor<String> fn) {
        if (ctx.profile() == null) return ResolvedValue.none();
        String enc = fn.extract(ctx.profile());
        if (blank(enc)) return ResolvedValue.none();
        try {
            String plain = encryptionService.decrypt(enc);
            return blank(plain) ? ResolvedValue.none() : ResolvedValue.fromProfile(plain);
        } catch (Exception e) {
            log.warn("Decrypt failed in DataResolver: {}", e.getMessage());
            return ResolvedValue.none();
        }
    }

    /** I-94 prefers the encrypted column; falls back to legacy plain column. */
    private ResolvedValue resolveI94(ResolutionContext ctx) {
        if (ctx.profile() == null) return ResolvedValue.none();
        String enc = ctx.profile().getI94NumberEnc();
        if (!blank(enc)) {
            try {
                String plain = encryptionService.decrypt(enc);
                if (!blank(plain)) return ResolvedValue.fromProfile(plain);
            } catch (Exception e) {
                log.warn("I-94 decrypt failed: {}", e.getMessage());
            }
        }
        // Legacy plain column fallback
        String plain = ctx.profile().getI94Number();
        return blank(plain) ? ResolvedValue.none() : ResolvedValue.fromProfile(plain);
    }

    /** Extracts a named field from currentAddressJson: { line1, line2, city, state, zip, country }. */
    @SuppressWarnings("unchecked")
    private ResolvedValue addressField(ResolutionContext ctx, String field) {
        if (ctx.profile() == null || blank(ctx.profile().getCurrentAddressJson())) {
            return ResolvedValue.none();
        }
        try {
            Map<String, Object> addr = objectMapper.readValue(ctx.profile().getCurrentAddressJson(), Map.class);
            Object val = addr.get(field);
            return (val instanceof String s && !s.isBlank())
                    ? ResolvedValue.fromProfile(s)
                    : ResolvedValue.none();
        } catch (Exception e) {
            log.debug("addressField '{}' parse error: {}", field, e.getMessage());
            return ResolvedValue.none();
        }
    }

    /** Extracts a field from the first entry of employmentJson array. */
    @SuppressWarnings("unchecked")
    private ResolvedValue employmentField(ResolutionContext ctx, String field) {
        if (ctx.profile() == null || blank(ctx.profile().getEmploymentJson())) {
            return ResolvedValue.none();
        }
        try {
            List<Map<String, Object>> list = objectMapper.readValue(ctx.profile().getEmploymentJson(), List.class);
            if (list.isEmpty()) return ResolvedValue.none();
            Object val = list.get(0).get(field);
            return (val instanceof String s && !s.isBlank())
                    ? ResolvedValue.fromProfile(s)
                    : ResolvedValue.none();
        } catch (Exception e) {
            log.debug("employmentField '{}' parse error: {}", field, e.getMessage());
            return ResolvedValue.none();
        }
    }

    // ── Org helpers ───────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface OrgExtractor { String extract(ImmOrg o); }

    private ResolvedValue orgStr(ImmOrg org, OrgExtractor fn) {
        if (org == null) return ResolvedValue.none();
        String val = fn.extract(org);
        return blank(val) ? ResolvedValue.none() : ResolvedValue.fromOrg(val);
    }

    // ── Attorney helpers ──────────────────────────────────────────────────────

    /** Returns the first bar number from AttorneyProfile.barNumbersJson. */
    @SuppressWarnings("unchecked")
    private ResolvedValue resolveBarNumber(ResolutionContext ctx) {
        if (ctx.attorneyProfile() == null || blank(ctx.attorneyProfile().getBarNumbersJson())) {
            return ResolvedValue.none();
        }
        try {
            List<Map<String, Object>> bars = objectMapper.readValue(
                    ctx.attorneyProfile().getBarNumbersJson(), List.class);
            if (bars.isEmpty()) return ResolvedValue.none();
            Object bn = bars.get(0).get("barNumber");
            return (bn instanceof String s && !s.isBlank())
                    ? ResolvedValue.fromProfile(s)
                    : ResolvedValue.none();
        } catch (Exception e) {
            log.debug("barNumber parse error: {}", e.getMessage());
            return ResolvedValue.none();
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
