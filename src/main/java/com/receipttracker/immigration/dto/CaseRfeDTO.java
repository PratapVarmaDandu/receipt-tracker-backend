package com.receipttracker.immigration.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record CaseRfeDTO(
        Long id,
        Long caseId,
        LocalDate issuedDate,
        LocalDate responseDeadline,
        String uscisCategory,
        String uscisNote,
        String status,
        LocalDateTime respondedAt,
        Long createdByUserId,
        LocalDateTime createdAt,
        long daysUntilDeadline
) {}
