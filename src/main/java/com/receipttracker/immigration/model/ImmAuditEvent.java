package com.receipttracker.immigration.model;

import com.receipttracker.model.User;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Append-only audit log for immigration case activity.
 * AuditService only exposes append() — no update or delete methods exist.
 * detail is a TEXT/JSON blob for structured context (old/new status, field names, etc.).
 */
@Entity
@Table(name = "imm_audit_events")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImmAuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "case_id", nullable = false)
    private ImmigrationCase immigrationCase;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id")
    private User actor; // null for system events

    @Column(name = "action", nullable = false, length = 100)
    private String action; // e.g. "CASE_CREATED", "STATUS_CHANGED", "PROFILE_UPDATED"

    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail; // JSON blob, optional structured context

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false)
    private FeedVisibility visibility = FeedVisibility.ALL;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { createdAt = LocalDateTime.now(); }
}
