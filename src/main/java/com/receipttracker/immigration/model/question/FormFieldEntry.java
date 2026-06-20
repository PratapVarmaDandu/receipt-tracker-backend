package com.receipttracker.immigration.model.question;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One question → one PDF field mapping within a form section.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FormFieldEntry {

    /** References a key in canonical-questions.json */
    private String questionKey;

    /** AcroForm field name in the USCIS PDF */
    private String pdfFieldName;

    public String getQuestionKey()          { return questionKey; }
    public void setQuestionKey(String v)    { questionKey = v; }

    public String getPdfFieldName()         { return pdfFieldName; }
    public void setPdfFieldName(String v)   { pdfFieldName = v; }
}
