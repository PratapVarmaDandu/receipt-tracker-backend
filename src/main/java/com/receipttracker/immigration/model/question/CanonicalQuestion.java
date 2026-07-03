package com.receipttracker.immigration.model.question;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Immutable config loaded from canonical-questions.json.
 * Not a JPA entity — loaded by CanonicalQuestionRegistry at startup.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CanonicalQuestion {

    /** Dot-separated key, e.g. "beneficiary.passportNumber" */
    private String key;

    /** Human-readable label shown in data-collection forms */
    private String label;

    /** Optional hint text */
    private String sublabel;

    /** BENEFICIARY | EMPLOYER | ATTORNEY */
    private String owner;

    /**
     * Documentation path showing where the value lives in canonical data objects.
     * e.g. "CanonicalProfile.passportsJson[current].numberEnc"
     * Used by DataResolver for routing; treated as documentation in the registry.
     */
    private String dataSource;

    /**
     * Logical grouping for data-collection wizard UI.
     * e.g. personal_info, passport_id, company_info, job_details
     */
    private String friendlySection;

    /** TEXT | TEXT_SENSITIVE | DATE | BOOLEAN | NUMBER | ADDRESS */
    private String type;

    private boolean required;

    /** Optional validation constraints — maxLength, pattern, min, max */
    private Map<String, Object> validation;

    /** True = value is AES-256-GCM encrypted at rest */
    private boolean encrypt;

    /** FormType values that use this question (e.g. ["I129","I485"]) */
    private List<String> formsUsing;

    /**
     * Where the value is persisted:
     *   "typed"   (default) — a dedicated column on CanonicalProfile / ImmOrg,
     *              resolved by an explicit case in DataResolver
     *   "generic" — the imm_canonical_answers key-value store; no code change
     *              needed to add such a question
     */
    private String storage;

    /**
     * Storage subject for generic questions: BENEFICIARY | EMPLOYER_ORG |
     * LAW_FIRM_ORG | CASE. When absent, derived from owner (BENEFICIARY →
     * BENEFICIARY, EMPLOYER → EMPLOYER_ORG, ATTORNEY → LAW_FIRM_ORG). Use CASE
     * for per-case values (e.g. job/LCA details) that must not be shared
     * across an org's cases.
     */
    private String subjectScope;

    /**
     * Name of a DerivationRegistry function that computes this question's value
     * from case data (e.g. "classificationSymbol"). Derived questions are never
     * included in questionnaires or write-back — they only produce prefill
     * answers with source="derived".
     */
    private String derivation;

    /**
     * Repeat-group spec for LIST questions — the answer is a JSON array of row
     * objects (aliases, prior stays, dependents). LIST questions must use
     * storage="generic": write-back stores the whole array in the generic
     * store; CanonicalProfile JSON columns are never patched.
     */
    private RepeatGroupSpec repeatGroup;

    /**
     * Attorney review-time answer (attestations, certifications, signatory
     * fields). Excluded from all questionnaires and from prefill resolution —
     * the attorney sets the value per package via the answer-override endpoint
     * (source stays "attorney_override"). Review summary groups these under
     * "ATTORNEY_REVIEW"; pre-generation check 4 still counts required ones.
     */
    private boolean reviewOnly;

    // ── Getters/setters (no Lombok — plain POJO for Jackson) ─────────────────

    public String getKey()             { return key; }
    public void setKey(String v)       { key = v; }

    public String getLabel()           { return label; }
    public void setLabel(String v)     { label = v; }

    public String getSublabel()        { return sublabel; }
    public void setSublabel(String v)  { sublabel = v; }

    public String getOwner()           { return owner; }
    public void setOwner(String v)     { owner = v; }

    public String getDataSource()      { return dataSource; }
    public void setDataSource(String v){ dataSource = v; }

    public String getFriendlySection() { return friendlySection; }
    public void setFriendlySection(String v) { friendlySection = v; }

    public String getType()            { return type; }
    public void setType(String v)      { type = v; }

    public boolean isRequired()        { return required; }
    public void setRequired(boolean v) { required = v; }

    public Map<String, Object> getValidation() { return validation; }
    public void setValidation(Map<String, Object> v) { validation = v; }

    public boolean isEncrypt()         { return encrypt; }
    public void setEncrypt(boolean v)  { encrypt = v; }

    public List<String> getFormsUsing() { return formsUsing; }
    public void setFormsUsing(List<String> v) { formsUsing = v; }

    public String getStorage()          { return storage; }
    public void setStorage(String v)    { storage = v; }

    public String getSubjectScope()     { return subjectScope; }
    public void setSubjectScope(String v) { subjectScope = v; }

    public String getDerivation()       { return derivation; }
    public void setDerivation(String v) { derivation = v; }

    public RepeatGroupSpec getRepeatGroup()        { return repeatGroup; }
    public void setRepeatGroup(RepeatGroupSpec v)  { repeatGroup = v; }

    public boolean isReviewOnly()       { return reviewOnly; }
    public void setReviewOnly(boolean v){ reviewOnly = v; }

    /** True when the value lives in the imm_canonical_answers generic store. */
    public boolean isGenericStorage()   { return "generic".equals(storage); }

    /** True when the value is computed by a DerivationRegistry function. */
    public boolean isDerived()          { return derivation != null && !derivation.isBlank(); }

    /** True for repeat-group questions whose answer is a JSON array of rows. */
    public boolean isList()             { return "LIST".equals(type); }
}
