package com.receipttracker.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionDTO {
    private Long id;
    private String planId;
    private String status;
    private LocalDateTime currentPeriodEnd;
    private LocalDateTime gracePeriodEndsAt;
    private LocalDate cancelEffectiveDate;
}
