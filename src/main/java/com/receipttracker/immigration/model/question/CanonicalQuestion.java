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
}
