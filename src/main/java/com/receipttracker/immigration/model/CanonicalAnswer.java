package com.receipttracker.immigration.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Generic key-value answer store for canonical questions that have no typed
 * column on CanonicalProfile / ImmOrg (questions with storage="generic" in
 * canonical-questions.json). Lets new form fields be added as pure config —
 * no entity column or migration required.
 *
 * Subject addressing:
 *   BENEFICIARY → subjectId = Beneficiary.id
 *   ORG         → subjectId = ImmOrg.id (employer or law firm)
 *   CASE        → subjectId = ImmigrationCase.id (per-case fields, e.g. job/LCA details)
 *
 * All ids are loose Longs — no JPA relations, per the cross-feature FK rule.
 */
@Entity
@Table(name = "imm_canonical_answers",
       uniqueConstraints = @UniqueConstraint(columnNames = {"subject_type", "subject_id", "question_key"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CanonicalAnswer {

    public static final String SUBJECT_BENEFICIARY = "BENEFICIARY";
    public static final String SUBJECT_ORG         = "ORG";
    public static final String SUBJECT_CASE        = "CASE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // BENEFICIARY | ORG | CASE
    @Column(name = "subject_type", length = 20, nullable = false)
    private String subjectType;

    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    @Column(name = "question_key", length = 200, nullable = false)
    private String questionKey;

    // Plaintext for non-sensitive; AES-256-GCM ciphertext for sensitive (encrypt=true)
    @Column(name = "value_json", columnDefinition = "TEXT")
    private String valueJson;

    // SHA-256 hex of plaintext — only set for sensitive (encrypt=true) fields
    @Column(name = "value_hash", length = 64)
    private String valueHash;

    @Column(name = "updated_by_user_id")
    private Long updatedByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
