package com.receipttracker.immigration.dto;

/**
 * barNumbers: list of { state, barNumber, admittedDate (yyyy-MM-dd) }
 * null fields are ignored (patch semantics — only non-null fields are updated).
 */
public record UpdateAttorneyProfileRequest(
        Object barNumbers,
        String bio
) {}
