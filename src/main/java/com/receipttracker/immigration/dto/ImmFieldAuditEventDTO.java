package com.receipttracker.immigration.dto;

import java.time.LocalDateTime;

public record ImmFieldAuditEventDTO(
        Long id,
        Long caseId,
        String entityType,
        Long entityId,
        String fieldKey,
        Long actorUserId,
        String action,
        String source,
        String oldValueHash,    // SHA-256 — only set when sensitive=true
        String newValueHash,    // SHA-256 — only set when sensitive=true
        boolean sensitive,
        String detail,
        LocalDateTime createdAt
) {}
