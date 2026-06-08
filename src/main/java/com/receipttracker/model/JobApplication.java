package com.receipttracker.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "job_applications")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class JobApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String companyName;

    @Column(nullable = false)
    private String jobTitle;

    private String jobUrl;
    private String location;
    private String salaryRange;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobApplicationStatus status = JobApplicationStatus.APPLIED;

    @Column(nullable = false)
    private LocalDate appliedDate;

    private LocalDate followUpDate;

    private String hrName;
    private String hrEmail;
    private String hrPhone;
    private String recruiterName;
    private String recruiterEmail;

    @Column(columnDefinition = "TEXT")
    private String jobDescription;

    @Column(columnDefinition = "TEXT")
    private String prepNotes;

    @Column(columnDefinition = "TEXT")
    private String notes;

    /** FK to Document vault — the resume version sent with this application. */
    private Long resumeDocumentId;

    /** Friendly label for which resume version was sent (e.g. "v3 – tailored for fintech"). */
    private String resumeVersion;

    @OneToMany(mappedBy = "jobApplication", cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
    private List<InterviewRound> interviewRounds = new ArrayList<>();

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt  = LocalDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
