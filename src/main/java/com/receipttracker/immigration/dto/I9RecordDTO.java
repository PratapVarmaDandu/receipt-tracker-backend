package com.receipttracker.immigration.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record I9RecordDTO(
        Long id,
        Long employerImmOrgId,
        String employeeEmail,
        String employeeName,
        String workAuthType,
        String documentTitle,
        String documentNumber,
        LocalDate expiryDate,
        LocalDate verifiedAt,
        LocalDate reverificationDue,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
