package com.receipttracker.immigration.model;

import com.receipttracker.model.User;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Tracks beneficiary consent grants and revocations.
 * Revocation triggers ConsentService to call GrantRepository.revokeMatching() for the affected case/relationship.
 * Never deleted — new rows are added for each grant/revoke action.
 */
@Entity
@Table(name = "imm_consent_records")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConsentRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "case_id", nullable = false)
    private ImmigrationCase immigrationCase;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "beneficiary_id", nullable = false)
    private Beneficiary beneficiary;

    // Which relationship type this consent covers (e.g. ATTORNEY, HR_ADMIN)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "covers_relationship", nullable = false, length = 50)
    private CaseRelationship coversRelationship;

    @Column(name = "granted", nullable = false)
    private boolean granted;

    @Column(name = "action_at", nullable = false)
    private LocalDateTime actionAt;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @PrePersist
    protected void onCreate() { if (actionAt == null) actionAt = LocalDateTime.now(); }
}
