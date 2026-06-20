package com.receipttracker.immigration.dto;

public record UpdateChecklistItemRequest(
        String status,        // PENDING | UPLOADED | WAIVED | VERIFIED
        Long documentId,      // nullable — vault document ID
        String waiverReason   // required when status = WAIVED
) {}
