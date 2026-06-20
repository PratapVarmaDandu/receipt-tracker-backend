package com.receipttracker.immigration.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Returned by the public GET /api/immigration/data-requests/{token} endpoint.
 * prefillData is the beneficiary's existing CanonicalProfile (may be null if not yet created).
 * The token itself is not echoed back — the client already has it in the URL.
 */
public record DataRequestPublicDTO(
        Long id,
        String caseNumber,
        String beneficiaryName,
        String beneficiaryEmail,
        String targetRelationship,
        List<String> sections,
        String status,
        LocalDateTime expiresAt,
        CanonicalProfileDTO prefillData
) {}
