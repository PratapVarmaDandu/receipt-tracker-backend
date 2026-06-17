package com.receipttracker.immigration.dto;

import java.time.LocalDateTime;

public record ConsentRecordDTO(
        Long id,
        Long caseId,
        String coversRelationship,
        boolean granted,
        LocalDateTime actionAt,
        String notes
) {}
