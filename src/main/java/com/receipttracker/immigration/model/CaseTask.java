package com.receipttracker.immigration.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Actionable task on an immigration case.
 * assignedToMemberId, completedByUserId, createdByUserId are loose refs (plain Long)
 * per cross-feature FK rule — they reference imm_org_members or users tables.
 */
@Entity
@Table(name = "imm_case_tasks")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CaseTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "case_id", nullable = false)
    private ImmigrationCase immigrationCase;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "due_date")
    private LocalDate dueDate;

    // loose ref to imm_org_members.id — null means unassigned
    @Column(name = "assigned_to_member_id")
    private Long assignedToMemberId;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    // loose ref to users.id
    @Column(name = "completed_by_user_id")
    private Long completedByUserId;

    @Column(name = "is_required", nullable = false)
    private boolean isRequired = false;

    // loose ref to users.id
    @Column(name = "created_by_user_id", nullable = false)
    private Long createdByUserId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist  protected void onCreate() { createdAt = updatedAt = LocalDateTime.now(); }
    @PreUpdate   protected void onUpdate()  { updatedAt = LocalDateTime.now(); }
}
