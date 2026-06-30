package com.receipttracker.immigration.dto;

import java.util.List;
import java.util.Map;

/**
 * Everything the mapping-builder UI needs to pair canonical questions with the real
 * AcroForm field names extracted from an uploaded form PDF.
 */
public record MappingBuilderDTO(
        Long versionId,
        String formType,
        List<String> pdfFieldNames,                 // real AcroForm field names extracted from the PDF
        List<Question> questions,                   // canonical questions used by this form
        Map<String, String> currentMapping          // questionKey → pdfFieldName (existing/seed)
) {
    public record Question(
            String key,
            String label,
            String owner,
            String section,
            String sectionLabel
    ) {}
}
