package com.receipttracker.immigration.dto;

import java.time.LocalDate;

public record CreateCaseTaskRequest(
        String title,
        String description,
        LocalDate dueDate,
        Long assignedToMemberId,
        boolean isRequired
) {}
