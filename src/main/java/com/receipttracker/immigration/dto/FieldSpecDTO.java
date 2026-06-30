package com.receipttracker.immigration.dto;

/**
 * One row of the field-name spec a form designer uses when building a static AcroForm replica:
 * name the PDF field exactly {@code fieldName} and the existing fill/auto-map pipeline handles
 * the rest. Generic across all form types.
 */
public record FieldSpecDTO(
        String fieldName,      // the AcroForm field name to use (sanitized question key)
        String questionKey,
        String label,
        String owner,
        String section,
        String sectionLabel,
        String type,
        boolean required
) {}
