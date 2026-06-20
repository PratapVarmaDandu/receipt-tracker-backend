package com.receipttracker.immigration.dto;

import java.time.LocalDateTime;

public record ChecklistItemDTO(
        Long id,
        Long caseId,
        Long templateId,
        String itemKey,
        String label,
        String category,
        boolean required,
        String status,
        Long documentId,
        String waiverReason,
        Long verifiedByUserId,
        LocalDateTime verifiedAt,
        int sortOrder,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
