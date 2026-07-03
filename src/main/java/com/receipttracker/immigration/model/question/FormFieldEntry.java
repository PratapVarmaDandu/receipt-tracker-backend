package com.receipttracker.immigration.model.question;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * One question → one PDF field mapping within a form section.
 *
 * Field-type metadata lives here, not on the question, because the same
 * canonical question can render as a text box on one form and a
 * checkbox/radio group on another.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FormFieldEntry {

    /** References a key in canonical-questions.json */
    private String questionKey;

    /** AcroForm field name in the USCIS PDF */
    private String pdfFieldName;

    /**
     * How the PDF field is filled: "text" (default) | "checkbox" | "radio".
     * checkbox — box is checked when the answer matches checkboxOnValue
     * radio    — answer (after valueMap translation) must equal one of the
     *            radio group's export values
     */
    private String pdfFieldType;

    /**
     * Checkbox only: the canonical answer value that checks this box
     * (compared case-insensitively). Lets a Yes/No question map to two
     * separate checkbox fields — one entry with checkboxOnValue "Yes", one
     * with "No". When absent, truthy values (true/yes/y/1/on/checked) check
     * the box.
     */
    private String checkboxOnValue;

    /**
     * Optional canonical value → PDF export value translation, applied before
     * setValue for text and radio fields (e.g. {"Male":"M","Female":"F"}).
     * Values without a mapping pass through unchanged.
     */
    private Map<String, String> valueMap;

    /**
     * Repeat-group (LIST question) binding: which row of the JSON-array answer
     * this PDF field reads (0-based). Must be set together with itemField and
     * only on entries whose questionKey is a LIST question. An index with no
     * corresponding row simply leaves the field unfilled.
     */
    private Integer repeatIndex;

    /**
     * Repeat-group binding: which property of row {@link #repeatIndex} this
     * PDF field reads — must be one of the question's repeatGroup itemField
     * keys. pdfFieldType/checkboxOnValue/valueMap apply to the extracted cell
     * value as usual.
     */
    private String itemField;

    public String getQuestionKey()          { return questionKey; }
    public void setQuestionKey(String v)    { questionKey = v; }

    public String getPdfFieldName()         { return pdfFieldName; }
    public void setPdfFieldName(String v)   { pdfFieldName = v; }

    public String getPdfFieldType()         { return pdfFieldType; }
    public void setPdfFieldType(String v)   { pdfFieldType = v; }

    public String getCheckboxOnValue()      { return checkboxOnValue; }
    public void setCheckboxOnValue(String v){ checkboxOnValue = v; }

    public Map<String, String> getValueMap() { return valueMap; }
    public void setValueMap(Map<String, String> v) { valueMap = v; }

    public Integer getRepeatIndex()         { return repeatIndex; }
    public void setRepeatIndex(Integer v)   { repeatIndex = v; }

    public String getItemField()            { return itemField; }
    public void setItemField(String v)      { itemField = v; }

    /** True when this entry binds to row/column of a LIST question's answer. */
    public boolean isRepeatEntry()          { return repeatIndex != null; }
}
