package com.receipttracker.immigration.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "imm_generated_pdf_packets")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GeneratedPdfPacket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // loose FK — cross-feature FK rule
    @Column(name = "package_id", nullable = false)
    private Long packageId;

    // loose FK — cross-feature FK rule
    @Column(name = "case_id", nullable = false)
    private Long caseId;

    // JSON: [{ "formType": "I129", "versionId": 1, "editionDate": "01/20/2025" }]
    @Column(name = "form_versions_used_json", columnDefinition = "TEXT")
    private String formVersionsUsedJson;

    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;

    // loose ref to users.id
    @Column(name = "generated_by_user_id", nullable = false)
    private Long generatedByUserId;

    // DRAFT | ATTORNEY_APPROVED | FILED
    @Column(name = "status", length = 50, nullable = false)
    private String status = "DRAFT";

    @Column(name = "attorney_approved_at")
    private LocalDateTime attorneyApprovedAt;

    // loose ref to users.id
    @Column(name = "attorney_approved_by")
    private Long attorneyApprovedBy;

    // relative path from storagePathResolver root, e.g. "pdf-packets/42/uuid.zip"
    @Column(name = "pdf_storage_key", length = 500)
    private String pdfStorageKey;

    // JSON: [{ "questionKey", "pdfField", "source", "versionId", "filled", "formType" }]
    @Column(name = "generation_audit_json", columnDefinition = "TEXT")
    private String generationAuditJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
