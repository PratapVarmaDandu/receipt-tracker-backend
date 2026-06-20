package com.receipttracker.immigration.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "imm_form_version_audit")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FormVersionAuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "form_type", length = 50, nullable = false)
    private String formType;

    @Column(name = "edition_date", length = 20)
    private String editionDate;

    // DOWNLOADED | APPROVED | DEPRECATED | MAPPING_UPDATED | CHECK_NO_CHANGE | CHECK_ERROR
    @Column(name = "action", length = 100, nullable = false)
    private String action;

    // loose ref to users.id — no FK per cross-feature FK rule
    @Column(name = "performed_by_user_id")
    private Long performedByUserId;

    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
