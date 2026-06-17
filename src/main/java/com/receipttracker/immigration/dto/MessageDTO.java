package com.receipttracker.immigration.dto;

import java.time.LocalDateTime;

public record MessageDTO(
        Long id,
        Long threadId,
        String channel,
        Long authorId,
        String authorName,
        String content,
        LocalDateTime createdAt
) {}
