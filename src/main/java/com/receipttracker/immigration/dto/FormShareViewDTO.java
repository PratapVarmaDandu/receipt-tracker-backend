package com.receipttracker.immigration.dto;

import java.time.LocalDateTime;

public record FormShareViewDTO(
        Long shareId,
        String recipientType,
        LocalDateTime expiresAt,
        FormInstanceDTO form
) {}
