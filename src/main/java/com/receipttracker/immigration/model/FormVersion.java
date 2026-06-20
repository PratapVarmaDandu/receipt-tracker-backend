package com.receipttracker.immigration.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "imm_form_versions")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FormVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "form_type", length = 50, nullable = false)
    private String formType;

    @Column(name = "edition_date", length = 20, nullable = false)
    private String editionDate;

    @Column(name = "downloaded_at")
    private LocalDateTime downloadedAt;

    @Column(name = "pdf_storage_key", length = 500)
    private String pdfStorageKey;

    // PENDING_REVIEW | APPROVED | DEPRECATED
    @Column(name = "status", length = 50, nullable = false)
    private String status = "PENDING_REVIEW";

    // loose ref to users.id — no FK per cross-feature FK rule
    @Column(name = "approved_by_user_id")
    private Long approvedByUserId;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "field_mapping_verified", nullable = false)
    private boolean fieldMappingVerified = false;

    // JSON array of AcroForm field names extracted from the downloaded PDF
    @Column(name = "pdf_field_names_json", columnDefinition = "TEXT")
    private String pdfFieldNamesJson;

    @Column(name = "release_notes", columnDefinition = "TEXT")
    private String releaseNotes;

    // JSON content of the updated form-field-mappings file uploaded by attorney
    @Column(name = "proposed_mapping_json", columnDefinition = "TEXT")
    private String proposedMappingJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
