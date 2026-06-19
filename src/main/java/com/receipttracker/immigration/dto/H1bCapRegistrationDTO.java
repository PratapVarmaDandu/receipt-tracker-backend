package com.receipttracker.immigration.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record H1bCapRegistrationDTO(
        Long id,
        Long caseId,
        String caseNumber,
        int registrationYear,
        String registrationNumber,
        Boolean selectedInLottery,
        LocalDate selectionDate,
        LocalDate registrationDate,
        LocalDateTime createdAt
) {}
