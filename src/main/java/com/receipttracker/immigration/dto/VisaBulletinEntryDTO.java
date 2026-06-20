package com.receipttracker.immigration.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record VisaBulletinEntryDTO(
        Long id,
        Integer bulletinYear,
        Integer bulletinMonth,
        String preferenceCategory,
        String countryOfChargeability,
        LocalDate finalActionDate,   // null = C (current)
        LocalDate datesForFiling,    // null = unavailable
        LocalDateTime scrapedAt
) {}
