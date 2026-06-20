package com.receipttracker.immigration.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ProfileDataRequestDTO(
        Long id,
        Long caseId,
        String targetRelationship,
        String token,
        List<String> sections,
        String status,
        LocalDateTime submittedAt,
        LocalDateTime expiresAt,
        LocalDateTime createdAt
) {}
