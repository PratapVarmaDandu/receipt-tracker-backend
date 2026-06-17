package com.receipttracker.immigration.dto;

import java.time.LocalDateTime;

/** daysUntil is negative if the date has passed; urgency: OVERDUE / DUE_SOON / UPCOMING */
public record KeyDateDTO(
        Long id,
        Long caseId,
        String dateType,
        String label,
        String date,
        long daysUntil,
        String urgency,
        boolean autoComputed,
        String notes,
        LocalDateTime createdAt
) {}
