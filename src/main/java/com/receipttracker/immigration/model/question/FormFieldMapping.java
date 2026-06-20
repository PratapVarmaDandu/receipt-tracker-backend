package com.receipttracker.immigration.model.question;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * Top-level config for one USCIS form loaded from form-field-mappings/{FormType}.json.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FormFieldMapping {

    /** e.g. "I129", "I485", "I140" */
    private String formType;

    /** section ID → section mapping (e.g. "part1_petitioner" → FormSectionMapping) */
    private Map<String, FormSectionMapping> sections;

    public String getFormType()                         { return formType; }
    public void setFormType(String v)                   { formType = v; }

    public Map<String, FormSectionMapping> getSections(){ return sections; }
    public void setSections(Map<String, FormSectionMapping> v) { sections = v; }
}
