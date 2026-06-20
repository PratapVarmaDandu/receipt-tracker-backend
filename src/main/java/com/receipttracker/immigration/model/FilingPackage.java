package com.receipttracker.immigration.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "imm_filing_packages")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FilingPackage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // loose FK — no JPA relationship per cross-feature FK rule
    @Column(name = "case_id", nullable = false)
    private Long caseId;

    @Column(name = "name", length = 200, nullable = false)
    private String name;

    // JSON array of form type strings e.g. ["I129","I140_EB2"]
    @Column(name = "selected_form_types_json", columnDefinition = "TEXT")
    private String selectedFormTypesJson;

    // DRAFT | QUESTIONNAIRES_SENT | ANSWERS_COLLECTED | ATTORNEY_REVIEW | APPROVED | GENERATED | FILED
    @Column(name = "status", length = 50, nullable = false)
    private String status = "DRAFT";

    @Column(name = "approved_by_user_id")
    private Long approvedByUserId;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "generated_pdf_packet_key", length = 500)
    private String generatedPdfPacketKey;

    @Column(name = "created_by_user_id", nullable = false)
    private Long createdByUserId;

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
