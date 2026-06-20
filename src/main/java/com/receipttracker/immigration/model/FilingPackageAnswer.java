package com.receipttracker.immigration.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "imm_filing_package_answers",
       uniqueConstraints = @UniqueConstraint(columnNames = {"package_id", "question_key"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FilingPackageAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "package_id", nullable = false)
    private FilingPackage filingPackage;

    @Column(name = "question_key", length = 200, nullable = false)
    private String questionKey;

    // Plaintext JSON string for non-sensitive; AES-256-GCM ciphertext for sensitive (encrypt=true)
    @Column(name = "value_json", columnDefinition = "TEXT")
    private String valueJson;

    // SHA-256 hex of plaintext — only set for sensitive (encrypt=true) fields
    @Column(name = "value_hash", length = 64)
    private String valueHash;

    // BENEFICIARY | EMPLOYER | ATTORNEY
    @Column(name = "owner", length = 50)
    private String owner;

    // profile | org | questionnaire | attorney_override
    @Column(name = "source", length = 50)
    private String source;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "answered_by_user_id")
    private Long answeredByUserId;

    @Column(name = "attorney_override_reason", columnDefinition = "TEXT")
    private String attorneyOverrideReason;

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
