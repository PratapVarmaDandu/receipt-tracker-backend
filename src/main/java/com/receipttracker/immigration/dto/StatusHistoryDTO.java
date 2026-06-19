package com.receipttracker.immigration.dto;

import java.time.LocalDateTime;

public record StatusHistoryDTO(
        Long id,
        Long caseId,
        String fromStatus,
        String toStatus,
        Long changedByUserId,
        String changedByName,   // resolved from User at query time; null for system transitions
        LocalDateTime changedAt,
        String note
) {}
