package com.receipttracker.immigration.dto;

import java.time.LocalDateTime;

public record FormShareDTO(
        Long id,
        Long formInstanceId,
        String token,
        String recipientEmail,
        String recipientType,
        LocalDateTime expiresAt,
        LocalDateTime createdAt
) {}
