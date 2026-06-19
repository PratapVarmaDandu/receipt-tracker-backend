package com.receipttracker.immigration.dto;

public record CapSeasonSummaryDTO(
        int registrationYear,
        int total,
        int selected,
        int notSelected,
        int pendingResult
) {}
