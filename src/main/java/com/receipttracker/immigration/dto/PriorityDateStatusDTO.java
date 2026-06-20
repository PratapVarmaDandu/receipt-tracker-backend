package com.receipttracker.immigration.dto;

import java.time.LocalDate;

public record PriorityDateStatusDTO(
        LocalDate priorityDate,
        String countryOfBirth,
        String preferenceCategory,
        LocalDate currentCutoff,     // null = C (current/immediately available)
        boolean isCurrent,
        Long monthsBehind            // null when isCurrent
) {}
