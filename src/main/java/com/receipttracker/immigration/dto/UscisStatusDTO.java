package com.receipttracker.immigration.dto;

import java.time.LocalDateTime;

public record UscisStatusDTO(
        Long id,
        Long caseId,
        LocalDateTime polledAt,
        String rawStatusText,
        String detectedStatus,
        boolean statusChanged
) {}
