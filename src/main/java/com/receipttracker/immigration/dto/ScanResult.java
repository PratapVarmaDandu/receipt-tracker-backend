package com.receipttracker.immigration.dto;

import java.util.List;
import java.util.Map;

public record ScanResult(
        String docTypeDetected,
        Map<String, FieldExtraction> extractedFields,
        List<String> lowConfidenceFields,
        String caseReceiptNumberSuggestion   // non-null only for I797_NOTICE scans with a receipt number
) {
    public record FieldExtraction(String value, double confidence, boolean needsReview) {}
}
