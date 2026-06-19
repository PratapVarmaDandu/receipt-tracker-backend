package com.receipttracker.immigration.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record CaseTaskDTO(
        Long id,
        Long caseId,
        String title,
        String description,
        LocalDate dueDate,
        Long assignedToMemberId,
        String assignedToName,
        LocalDateTime completedAt,
        Long completedByUserId,
        String completedByName,
        boolean isRequired,
        Long createdByUserId,
        String createdByName,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        boolean overdue
) {}
