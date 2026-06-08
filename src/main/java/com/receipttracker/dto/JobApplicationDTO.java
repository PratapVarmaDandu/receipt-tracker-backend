package com.receipttracker.dto;

import com.receipttracker.model.JobApplicationStatus;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class JobApplicationDTO {
    private Long id;
    private String companyName;
    private String jobTitle;
    private String jobUrl;
    private String location;
    private String salaryRange;
    private JobApplicationStatus status;
    private LocalDate appliedDate;
    private LocalDate followUpDate;
    private String hrName;
    private String hrEmail;
    private String hrPhone;
    private String recruiterName;
    private String recruiterEmail;
    private String jobDescription;
    private String prepNotes;
    private String notes;
    private Long resumeDocumentId;
    private String resumeVersion;
    private String resumeDocumentTitle;
    private List<InterviewRoundDTO> interviewRounds;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    // Computed fields
    private LocalDateTime nextInterviewAt;
    private boolean followUpDue;
}
