package com.receipttracker.immigration.dto;

import java.time.LocalDateTime;

public record FilingPackageQuestionnaireDTO(
        Long id,
        Long packageId,
        String targetRelationship,
        String token,
        String status,
        LocalDateTime submittedAt,
        LocalDateTime expiresAt,
        LocalDateTime createdAt,
        int questionCount,
        int answeredCount
) {}
