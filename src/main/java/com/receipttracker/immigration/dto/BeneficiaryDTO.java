package com.receipttracker.immigration.dto;

import java.time.LocalDateTime;

public record BeneficiaryDTO(
        Long id,
        Long userId,
        String userName,
        String userEmail,
        LocalDateTime createdAt,
        boolean hasProfile
) {}
