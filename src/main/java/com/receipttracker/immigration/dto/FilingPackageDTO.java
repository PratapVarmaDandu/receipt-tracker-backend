package com.receipttracker.immigration.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record FilingPackageDTO(
        Long id,
        Long caseId,
        String name,
        List<String> selectedFormTypes,
        String status,
        LocalDateTime approvedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<FilingPackageQuestionnaireDTO> questionnaires,
        // per-owner completeness: { "BENEFICIARY": 72, "EMPLOYER": 50, "ATTORNEY": 100 }
        Map<String, Integer> completenessPercent
) {}
