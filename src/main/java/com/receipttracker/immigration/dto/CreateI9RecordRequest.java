package com.receipttracker.immigration.dto;

public record CreateI9RecordRequest(
        String employeeEmail,
        String employeeName,
        String workAuthType,
        String documentTitle,
        String documentNumber,
        String expiryDate,          // ISO date string, nullable
        String verifiedAt,          // ISO date string
        String reverificationDue    // ISO date string, nullable
) {}
