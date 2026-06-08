package com.receipttracker.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class CreateJobApplicationRequest {
    private String companyName;
    private String jobTitle;
    private String jobUrl;
    private String location;
    private String salaryRange;
    private String status;
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
}
