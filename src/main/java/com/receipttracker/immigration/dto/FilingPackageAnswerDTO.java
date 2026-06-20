package com.receipttracker.immigration.dto;

import java.time.LocalDateTime;

public record FilingPackageAnswerDTO(
        Long id,
        String questionKey,
        String displayValue,  // plaintext for non-sensitive; "••••••" for sensitive
        String owner,
        String source,
        boolean hasValue,
        boolean sensitive,
        LocalDateTime verifiedAt,
        String attorneyOverrideReason,
        LocalDateTime updatedAt
) {}
