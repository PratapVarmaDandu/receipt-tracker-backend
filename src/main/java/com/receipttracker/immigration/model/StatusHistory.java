package com.receipttracker.immigration.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Immutable audit trail of case status transitions.
 * changedByUserId is a loose ref (plain Long) — cross-feature FK rule.
 * Rows are append-only; no updates or deletes.
 */
@Entity
@Table(name = "imm_status_history")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "case_id", nullable = false)
    private ImmigrationCase immigrationCase;

    @Column(name = "from_status", length = 50)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, length = 50)
    private String toStatus;

    // loose ref to users.id
    @Column(name = "changed_by_user_id")
    private Long changedByUserId;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @PrePersist
    protected void onCreate() {
        if (changedAt == null) changedAt = LocalDateTime.now();
    }
}
