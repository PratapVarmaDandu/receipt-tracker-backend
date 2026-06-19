package com.receipttracker.immigration.dto;

import java.time.LocalDate;

public record UpdateCaseTaskRequest(
        String title,
        String description,
        LocalDate dueDate,
        Long assignedToMemberId,
        Boolean isRequired
) {}
