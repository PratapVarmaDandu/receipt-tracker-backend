package com.receipttracker.immigration.dto;

import java.time.LocalDateTime;

public record FormVersionAuditEventDTO(
    Long id,
    String formType,
    String editionDate,
    String action,
    Long performedByUserId,
    String detail,
    LocalDateTime createdAt
) {}
