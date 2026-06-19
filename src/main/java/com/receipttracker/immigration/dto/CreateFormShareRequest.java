package com.receipttracker.immigration.dto;

public record CreateFormShareRequest(
        String recipientEmail,
        String recipientType,   // EMPLOYER | BENEFICIARY
        int expiryDays
) {}
