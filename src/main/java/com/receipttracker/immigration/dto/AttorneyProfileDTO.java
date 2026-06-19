package com.receipttracker.immigration.dto;

import java.time.LocalDateTime;

/**
 * barNumbers: list of { state, barNumber, admittedDate (yyyy-MM-dd) }
 * Returned as native JSON via Jackson; stored as TEXT in imm_attorney_profiles.
 */
public record AttorneyProfileDTO(
        Long id,
        Long immOrgMemberId,
        Object barNumbers,   // null when no profile exists yet
        String bio,
        String signatureImageKey,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
