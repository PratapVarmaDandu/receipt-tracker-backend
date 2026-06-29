package com.receipttracker.immigration.service;

import com.receipttracker.immigration.model.CanonicalProfile;
import com.receipttracker.immigration.model.FormType;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps canonical profile data into form-specific field sets.
 * This service organizes beneficiary data into a reference structure — it does NOT
 * generate, submit, or advise on official USCIS/DOS filings. No method here selects
 * a form or recommends an immigration action; field selection is informational only.
 *
 * TODO: Every field mapping below must be verified against the current official form
 * instructions before use in a production workflow.
 */
@Service
public class FormMappingService {

    // ── Required fields per form type (used for completeness calculation) ─────
    private static final List<String> I129_REQUIRED = List.of(
            "beneficiaryLastName", "beneficiaryFirstName", "dateOfBirth",
            "countryOfBirth", "citizenshipCountry", "passportNumber",
            "passportExpiryDate", "currentNonimmigrantStatus"
    );

    private static final List<String> DS160_REQUIRED = List.of(
            "surname", "givenNames", "dateOfBirth", "countryOfBirth",
            "nationality", "gender", "passportNumber",
            "passportIssueDate", "passportExpiryDate"
    );

    // TODO: verify required field lists against official form instructions before use in production
    private static final List<String> I485_REQUIRED = List.of(
            "legalLastName", "legalFirstName", "dateOfBirth", "countryOfBirth",
            "citizenshipCountry", "alienNumber", "ssn"
    );

    private static final List<String> I765_REQUIRED = List.of(
            "legalLastName", "legalFirstName", "dateOfBirth", "countryOfBirth",
            "citizenshipCountry", "ssn"
    );

    private static final List<String> I131_REQUIRED = List.of(
            "legalLastName", "legalFirstName", "dateOfBirth", "citizenshipCountry",
            "passportNumber", "passportExpiryDate"
    );

    private static final List<String> I140_REQUIRED = List.of(
            "legalLastName", "legalFirstName", "dateOfBirth", "countryOfBirth",
            "citizenshipCountry", "passportNumber"
    );

    private static final List<String> I539_REQUIRED = List.of(
            "legalLastName", "legalFirstName", "dateOfBirth", "countryOfBirth",
            "citizenshipCountry", "passportNumber", "i94AdmissionNumber",
            "currentNonimmigrantStatus"
    );

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Produces the field data map for the given form type from the supplied profile.
     * Returns a LinkedHashMap to preserve insertion order in JSON serialization.
     */
    public Map<String, Object> mapFields(FormType formType, CanonicalProfile p) {
        return switch (formType) {
            case I129  -> mapI129(p);
            case DS160 -> mapDs160(p);
            case I485  -> mapI485(p);
            case I765  -> mapI765(p);
            case I131  -> mapI131(p);
            case I140  -> mapI140(p);
            case I539  -> mapI539(p);
            // G-28, I-290B, I-693, PERM: no beneficiary profile fields map directly; return empty
            case G28, I290B, I693, PERM -> new LinkedHashMap<>();
        };
    }

    /** Returns 0–100 completeness score for the given field map and form type. */
    public int computeCompleteness(FormType formType, Map<String, Object> fields) {
        List<String> required = switch (formType) {
            case I129  -> I129_REQUIRED;
            case DS160 -> DS160_REQUIRED;
            case I485  -> I485_REQUIRED;
            case I765  -> I765_REQUIRED;
            case I131  -> I131_REQUIRED;
            case I140  -> I140_REQUIRED;
            case I539  -> I539_REQUIRED;
            case G28, I290B, I693, PERM -> List.of();
        };
        if (required.isEmpty()) return 0;
        long filled = required.stream()
                .filter(k -> fields.get(k) != null && !fields.get(k).toString().isBlank())
                .count();
        return (int) Math.round(100.0 * filled / required.size());
    }

    // ── I-129 mapping ─────────────────────────────────────────────────────────

    private Map<String, Object> mapI129(CanonicalProfile p) {
        Map<String, Object> m = new LinkedHashMap<>();

        // Part 3 — Alien Information
        // TODO: verify field against official form instruction (I-129 Part 3, Item 1a)
        m.put("beneficiaryLastName",  p.getLegalLastName());
        // TODO: verify field against official form instruction (I-129 Part 3, Item 1b)
        m.put("beneficiaryFirstName", p.getLegalFirstName());
        // TODO: verify field against official form instruction (I-129 Part 3, Item 1c)
        m.put("beneficiaryMiddleName", p.getMiddleName());

        // TODO: verify field against official form instruction (I-129 Part 3, Item 3)
        m.put("dateOfBirth", str(p.getDateOfBirth()));
        // TODO: verify field against official form instruction (I-129 Part 3, Item 4)
        m.put("countryOfBirth", p.getCountryOfBirth());
        // TODO: verify field against official form instruction (I-129 Part 3, Item 5)
        m.put("citizenshipCountry", p.getCitizenshipCountry());

        // Travel document
        // TODO: verify field against official form instruction (I-129 Part 3, Item 6)
        m.put("passportNumber", p.getPassportNumberEnc() != null ? "[encrypted]" : null);
        // TODO: verify field against official form instruction (I-129 Part 3, Item 7)
        m.put("passportExpiryDate", str(p.getPassportExpiryDate()));

        // US entry & status
        // TODO: verify field against official form instruction (I-129 Part 3, Item 10)
        m.put("portOfEntry", p.getPortOfEntry());
        // TODO: verify field against official form instruction (I-129 Part 3, Item 11)
        m.put("dateOfLastEntry", str(p.getEntryDate()));
        // TODO: verify field against official form instruction (I-129 Part 3, Item 12)
        m.put("i94AdmissionNumber", p.getI94Number());
        // TODO: verify field against official form instruction (I-129 Part 3, Item 13)
        m.put("currentNonimmigrantStatus", p.getCurrentVisaType());
        // TODO: verify field against official form instruction (I-129 Part 3, Item 14)
        m.put("currentStatusExpiryDate", str(p.getCurrentVisaExpiry()));

        return m;
    }

    // ── DS-160 mapping ────────────────────────────────────────────────────────

    private Map<String, Object> mapDs160(CanonicalProfile p) {
        Map<String, Object> m = new LinkedHashMap<>();

        // Personal Information section
        // TODO: verify field against official form instruction (DS-160 Personal Information 1)
        m.put("surname", p.getLegalLastName());
        // TODO: verify field against official form instruction (DS-160 Personal Information 2)
        m.put("givenNames", join(p.getLegalFirstName(), p.getMiddleName()));
        // TODO: verify field against official form instruction (DS-160 Personal Information 5)
        m.put("dateOfBirth", str(p.getDateOfBirth()));
        // TODO: verify field against official form instruction (DS-160 Personal Information 6)
        m.put("countryOfBirth", p.getCountryOfBirth());
        // TODO: verify field against official form instruction (DS-160 Personal Information 7)
        m.put("nationality", p.getCitizenshipCountry());
        // TODO: verify field against official form instruction (DS-160 Personal Information 4)
        m.put("gender", p.getGender());

        // Travel document section
        // TODO: verify field against official form instruction (DS-160 Travel Document 1)
        m.put("passportNumber", p.getPassportNumberEnc() != null ? "[encrypted]" : null);
        // TODO: verify field against official form instruction (DS-160 Travel Document 2)
        m.put("passportIssueDate", str(p.getPassportIssueDate()));
        // TODO: verify field against official form instruction (DS-160 Travel Document 3)
        m.put("passportExpiryDate", str(p.getPassportExpiryDate()));
        // TODO: verify field against official form instruction (DS-160 Travel Document 4)
        m.put("passportIssuingCountry", p.getPassportCountry());

        // Contact
        m.put("phone", p.getPhone());

        return m;
    }

    // ── I-485 mapping ─────────────────────────────────────────────────────────

    private Map<String, Object> mapI485(CanonicalProfile p) {
        Map<String, Object> m = new LinkedHashMap<>();
        // TODO: verify field against official form instruction (I-485 Part 1)
        m.put("legalLastName",       p.getLegalLastName());
        m.put("legalFirstName",      p.getLegalFirstName());
        m.put("middleName",          p.getMiddleName());
        m.put("dateOfBirth",         str(p.getDateOfBirth()));
        m.put("countryOfBirth",      p.getCountryOfBirth());
        m.put("citizenshipCountry",  p.getCitizenshipCountry());
        m.put("gender",              p.getGender());
        // Sensitive — marker only; decrypted at PDF generation time
        m.put("alienNumber",  p.getAlienNumberEnc()  != null ? "[encrypted]" : null);
        m.put("ssn",          p.getSsnEnc()          != null ? "[encrypted]" : null);
        m.put("passportNumber", p.getPassportNumberEnc() != null ? "[encrypted]" : null);
        m.put("passportExpiryDate",  str(p.getPassportExpiryDate()));
        m.put("i94AdmissionNumber",  p.getI94Number());
        m.put("portOfEntry",         p.getPortOfEntry());
        m.put("lastEntryDate",       str(p.getEntryDate()));
        m.put("currentVisaType",     p.getCurrentVisaType());
        m.put("currentVisaExpiry",   str(p.getCurrentVisaExpiry()));
        return m;
    }

    // ── I-765 mapping ─────────────────────────────────────────────────────────

    private Map<String, Object> mapI765(CanonicalProfile p) {
        Map<String, Object> m = new LinkedHashMap<>();
        // TODO: verify field against official form instruction (I-765 Part 2)
        m.put("legalLastName",      p.getLegalLastName());
        m.put("legalFirstName",     p.getLegalFirstName());
        m.put("middleName",         p.getMiddleName());
        m.put("dateOfBirth",        str(p.getDateOfBirth()));
        m.put("countryOfBirth",     p.getCountryOfBirth());
        m.put("citizenshipCountry", p.getCitizenshipCountry());
        m.put("gender",             p.getGender());
        m.put("ssn",        p.getSsnEnc()         != null ? "[encrypted]" : null);
        m.put("alienNumber", p.getAlienNumberEnc() != null ? "[encrypted]" : null);
        m.put("eadCategory",     p.getEadCategory());
        m.put("eadCaseNumber",   p.getEadCaseNumber());
        return m;
    }

    // ── I-131 mapping ─────────────────────────────────────────────────────────

    private Map<String, Object> mapI131(CanonicalProfile p) {
        Map<String, Object> m = new LinkedHashMap<>();
        // TODO: verify field against official form instruction (I-131 Part 1)
        m.put("legalLastName",      p.getLegalLastName());
        m.put("legalFirstName",     p.getLegalFirstName());
        m.put("middleName",         p.getMiddleName());
        m.put("dateOfBirth",        str(p.getDateOfBirth()));
        m.put("citizenshipCountry", p.getCitizenshipCountry());
        m.put("alienNumber",   p.getAlienNumberEnc()     != null ? "[encrypted]" : null);
        m.put("passportNumber", p.getPassportNumberEnc() != null ? "[encrypted]" : null);
        m.put("passportExpiryDate", str(p.getPassportExpiryDate()));
        m.put("currentVisaType",    p.getCurrentVisaType());
        return m;
    }

    // ── I-140 mapping ─────────────────────────────────────────────────────────

    private Map<String, Object> mapI140(CanonicalProfile p) {
        Map<String, Object> m = new LinkedHashMap<>();
        // TODO: verify field against official form instruction (I-140 Part 3)
        m.put("legalLastName",      p.getLegalLastName());
        m.put("legalFirstName",     p.getLegalFirstName());
        m.put("middleName",         p.getMiddleName());
        m.put("dateOfBirth",        str(p.getDateOfBirth()));
        m.put("countryOfBirth",     p.getCountryOfBirth());
        m.put("citizenshipCountry", p.getCitizenshipCountry());
        m.put("alienNumber",    p.getAlienNumberEnc()     != null ? "[encrypted]" : null);
        m.put("passportNumber", p.getPassportNumberEnc()  != null ? "[encrypted]" : null);
        return m;
    }

    // ── I-539 mapping ─────────────────────────────────────────────────────────

    private Map<String, Object> mapI539(CanonicalProfile p) {
        Map<String, Object> m = new LinkedHashMap<>();
        // TODO: verify field against official form instruction (I-539 Part 1)
        m.put("legalLastName",      p.getLegalLastName());
        m.put("legalFirstName",     p.getLegalFirstName());
        m.put("middleName",         p.getMiddleName());
        m.put("dateOfBirth",        str(p.getDateOfBirth()));
        m.put("countryOfBirth",     p.getCountryOfBirth());
        m.put("citizenshipCountry", p.getCitizenshipCountry());
        m.put("alienNumber",    p.getAlienNumberEnc()     != null ? "[encrypted]" : null);
        m.put("passportNumber", p.getPassportNumberEnc()  != null ? "[encrypted]" : null);
        m.put("passportExpiryDate",         str(p.getPassportExpiryDate()));
        m.put("i94AdmissionNumber",         p.getI94Number());
        m.put("currentNonimmigrantStatus",  p.getCurrentVisaType());
        m.put("currentStatusExpiryDate",    str(p.getCurrentVisaExpiry()));
        m.put("portOfEntry",                p.getPortOfEntry());
        m.put("lastEntryDate",              str(p.getEntryDate()));
        return m;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String str(Object o) { return o != null ? o.toString() : null; }

    private String join(String a, String b) {
        if (a == null && b == null) return null;
        if (a == null) return b;
        if (b == null || b.isBlank()) return a;
        return a + " " + b;
    }
}
