package com.receipttracker.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "interview_rounds")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InterviewRound {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_application_id", nullable = false)
    private JobApplication jobApplication;

    @Column(nullable = false)
    private String roundName;

    private LocalDateTime scheduledAt;

    @Enumerated(EnumType.STRING)
    private InterviewFormat format;

    private String interviewerName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InterviewOutcome outcome = InterviewOutcome.PENDING;

    @Column(columnDefinition = "TEXT")
    private String notes;

    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        if (outcome == null) outcome = InterviewOutcome.PENDING;
    }
}
