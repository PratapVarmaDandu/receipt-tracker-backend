package com.receipttracker.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CreateInterviewRoundRequest {
    private String roundName;
    private LocalDateTime scheduledAt;
    private String format;
    private String interviewerName;
    private String outcome;
    private String notes;
}
