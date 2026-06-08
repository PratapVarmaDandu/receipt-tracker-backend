package com.receipttracker.dto;

import com.receipttracker.model.InterviewFormat;
import com.receipttracker.model.InterviewOutcome;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class InterviewRoundDTO {
    private Long id;
    private Long jobApplicationId;
    private String roundName;
    private LocalDateTime scheduledAt;
    private InterviewFormat format;
    private String interviewerName;
    private InterviewOutcome outcome;
    private String notes;
    private LocalDateTime createdAt;
}
