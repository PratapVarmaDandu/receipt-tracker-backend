package com.receipttracker.immigration.dto;

import java.time.LocalDateTime;

public record ActivityFeedItemDTO(
        Long id,
        String action,
        String actorName,
        String detail,
        LocalDateTime createdAt
) {}
