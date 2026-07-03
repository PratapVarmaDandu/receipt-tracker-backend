package com.receipttracker.immigration.service;

import com.receipttracker.immigration.model.question.FormFieldEntry;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Applies one canonical answer value to a PDF AcroForm field according to the
 * mapping entry's pdfFieldType — the type-aware half of the PDF fill loop.
 *
 * Field types:
 *   text (default) — setValue after valueMap translation
 *   checkbox       — check()/unCheck() based on checkboxOnValue match
 *   radio          — setValue with the translated export value; PDFBox throws
 *                    if it isn't one of the group's on-values (caught → false)
 */
public final class PdfFieldApplier {

    private static final Logger log = LoggerFactory.getLogger(PdfFieldApplier.class);

    public static final Set<String> VALID_FIELD_TYPES = Set.of("text", "checkbox", "radio");

    /** Values that check a checkbox when no explicit checkboxOnValue is configured. */
    private static final Set<String> TRUTHY = Set.of("true", "yes", "y", "1", "on", "checked");

    private PdfFieldApplier() {}

    /**
     * Fills one field. Returns true when the field was written (checking OR
     * explicitly un-checking a checkbox both count); false when the field is
     * missing from the PDF or PDFBox rejected the value.
     */
    public static boolean apply(PDAcroForm acroForm, FormFieldEntry entry, String value) {
        PDField field = acroForm.getField(entry.getPdfFieldName());
        if (field == null) {
            log.debug("PDF field '{}' not found in form", entry.getPdfFieldName());
            return false;
        }
        try {
            if ("checkbox".equals(entry.getPdfFieldType()) && field instanceof PDCheckBox cb) {
                if (isCheckboxOn(value, entry.getCheckboxOnValue())) cb.check();
                else cb.unCheck();
                return true;
            }
            // text, radio, and checkbox entries whose PDF field turned out not
            // to be a PDCheckBox: translated setValue
            field.setValue(resolvePdfValue(entry, value));
            return true;
        } catch (Exception e) {
            log.warn("Could not fill field '{}' (type {}): {}",
                     entry.getPdfFieldName(),
                     entry.getPdfFieldType() != null ? entry.getPdfFieldType() : "text",
                     e.getMessage());
            return false;
        }
    }

    /**
     * Translates a canonical value through the entry's valueMap (exact match
     * first, then case-insensitive). Unmapped values pass through unchanged.
     */
    static String resolvePdfValue(FormFieldEntry entry, String value) {
        Map<String, String> map = entry.getValueMap();
        if (map == null || value == null) return value;
        if (map.containsKey(value)) return map.get(value);
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(value)) return e.getValue();
        }
        return value;
    }

    /**
     * True when the answer should check this box: matches checkboxOnValue
     * case-insensitively when configured, otherwise any truthy value.
     */
    static boolean isCheckboxOn(String value, String onValue) {
        if (value == null || value.isBlank()) return false;
        if (onValue != null && !onValue.isBlank()) {
            return value.trim().equalsIgnoreCase(onValue.trim());
        }
        return TRUTHY.contains(value.trim().toLowerCase(Locale.ROOT));
    }
}
