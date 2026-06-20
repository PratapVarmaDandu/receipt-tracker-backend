package com.receipttracker.immigration.dto;

import java.time.LocalDateTime;

public record ConflictSummaryDTO(
        Long id,
        String caseNumber,
        String caseType,
        String status,
        LocalDateTime createdAt
) {}
