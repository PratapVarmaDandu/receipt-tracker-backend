package com.receipttracker.immigration.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "imm_case_rfes")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CaseRfe {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "case_id", nullable = false)
    private ImmigrationCase immigrationCase;

    @Column(name = "issued_date", nullable = false)
    private LocalDate issuedDate;

    @Column(name = "response_deadline", nullable = false)
    private LocalDate responseDeadline;

    @Column(name = "uscis_category", length = 200)
    private String uscisCategory;

    @Column(name = "uscis_note", columnDefinition = "TEXT")
    private String uscisNote;

    // OPEN | RESPONDED | WITHDRAWN | DISMISSED
    @Column(name = "status", nullable = false)
    private String status = "OPEN";

    @Column(name = "responded_at")
    private LocalDateTime respondedAt;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
