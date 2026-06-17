package com.receipttracker.immigration.dto;

import java.time.LocalDateTime;

/**
 * fieldData is the deserialized form field map — returned as a native JSON object
 * so the frontend can iterate over entries without an extra parse step.
 */
public record FormInstanceDTO(
        Long id,
        Long caseId,
        String formType,
        String formTypeLabel,
        String status,
        Object fieldData,   // deserialized from TEXT column via ObjectMapper
        int completeness,
        LocalDateTime submittedAt,
        String notes,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
