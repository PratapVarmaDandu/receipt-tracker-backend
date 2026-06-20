package com.receipttracker.immigration.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "imm_filing_package_questionnaires")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FilingPackageQuestionnaire {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "package_id", nullable = false)
    private FilingPackage filingPackage;

    // BENEFICIARY | EMPLOYER | ATTORNEY
    @Column(name = "target_relationship", length = 50, nullable = false)
    private String targetRelationship;

    @Column(name = "token", unique = true, nullable = false)
    private String token;

    // JSON array of question keys assigned to this questionnaire e.g. ["beneficiary.lastName",...]
    @Column(name = "questionnaire_spec_json", columnDefinition = "TEXT")
    private String questionnaireSpecJson;

    // PENDING | SUBMITTED | EXPIRED
    @Column(name = "status", length = 50, nullable = false)
    private String status = "PENDING";

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "submitted_by_user_id")
    private Long submittedByUserId;

    // JSON map of submitted answers: { "question.key": "value" }
    @Column(name = "submitted_answers_json", columnDefinition = "TEXT")
    private String submittedAnswersJson;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
