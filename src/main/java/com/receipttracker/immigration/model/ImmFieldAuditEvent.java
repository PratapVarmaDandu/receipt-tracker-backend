package com.receipttracker.immigration.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Append-only field-level audit log for immigration data.
 * Sensitive values are stored as SHA-256 hashes only — never plaintext.
 * case_id is a loose Long reference (cross-feature FK rule); nullable for profile-level events.
 */
@Entity
@Table(name = "imm_field_audit_events")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImmFieldAuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Loose reference — nullable for profile-level events not tied to a specific case
    @Column(name = "case_id")
    private Long caseId;

    @Column(name = "entity_type", nullable = false, length = 100)
    private String entityType;

    @Column(name = "entity_id")
    private Long entityId;

    @Column(name = "field_key", nullable = false, length = 200)
    private String fieldKey;

    // Loose reference to users.id — no FK per cross-feature FK rule
    @Column(name = "actor_user_id")
    private Long actorUserId;

    // CHANGED | SCANNED | QUESTIONNAIRE_SUBMITTED | PDF_GENERATED | ATTORNEY_APPROVED | FORM_VERSION_APPROVED
    @Column(name = "action", nullable = false, length = 100)
    private String action;

    // direct_edit | ocr_scan | questionnaire | attorney_override | system
    @Column(name = "source", nullable = false, length = 100)
    private String source;

    // SHA-256 of old value — only populated when isSensitive=true
    @Column(name = "old_value_hash", length = 64)
    private String oldValueHash;

    // SHA-256 of new value — only populated when isSensitive=true
    @Column(name = "new_value_hash", length = 64)
    private String newValueHash;

    // true for passport, SSN, A-number, I-94, EAD card number
    @Column(name = "is_sensitive", nullable = false)
    private boolean sensitive = false;

    // Non-sensitive context: JSON or free text; never contains raw sensitive values
    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
