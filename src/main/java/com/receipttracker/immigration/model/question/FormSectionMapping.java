package com.receipttracker.immigration.model.question;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * One part/section of a USCIS form (e.g. part1_petitioner in I-129).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FormSectionMapping {

    /** BENEFICIARY | EMPLOYER | ATTORNEY — who fills this section */
    private String owner;

    private List<FormFieldEntry> fields;

    public String getOwner()                   { return owner; }
    public void setOwner(String v)             { owner = v; }

    public List<FormFieldEntry> getFields()    { return fields; }
    public void setFields(List<FormFieldEntry> v) { fields = v; }
}
