package com.receipttracker.immigration.model.question;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * One column of a repeat-group (LIST) question — e.g. "lastName" within
 * beneficiary.aliases. Loaded from canonical-questions.json.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RepeatItemField {

    /** Property name inside each row object of the JSON-array answer */
    private String key;

    /** Column label shown in the questionnaire row editor */
    private String label;

    /** TEXT | DATE | NUMBER | SELECT */
    private String type;

    /** True = this field must be filled for a row to count as complete */
    private boolean required;

    /** SELECT only: allowed values */
    private List<String> options;

    public String getKey()               { return key; }
    public void setKey(String v)         { key = v; }

    public String getLabel()             { return label; }
    public void setLabel(String v)       { label = v; }

    public String getType()              { return type; }
    public void setType(String v)        { type = v; }

    public boolean isRequired()          { return required; }
    public void setRequired(boolean v)   { required = v; }

    public List<String> getOptions()     { return options; }
    public void setOptions(List<String> v) { options = v; }
}
