package com.receipttracker.immigration.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "imm_checklist_items")
@Data @NoArgsConstructor @AllArgsConstructor
public class ChecklistItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_id", nullable = false)
    private Long caseId; // loose ref — cross-phase FK rule

    @Column(name = "template_id")
    private Long templateId; // loose ref, nullable (null for manually added items)

    @Column(name = "item_key", length = 100, nullable = false)
    private String itemKey;

    @Column(name = "label", length = 500, nullable = false)
    private String label;

    @Column(name = "category", length = 100, nullable = false)
    private String category;

    @Column(name = "required", nullable = false)
    private boolean required;

    @Column(name = "status", length = 50, nullable = false)
    private String status = "PENDING"; // PENDING | UPLOADED | WAIVED | VERIFIED

    @Column(name = "document_id")
    private Long documentId; // loose ref to document vault, nullable

    @Column(name = "waiver_reason", columnDefinition = "TEXT")
    private String waiverReason;

    @Column(name = "verified_by_user_id")
    private Long verifiedByUserId; // loose ref, nullable

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

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
