package com.receipttracker.immigration.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * Tracks the mapping of a beneficiary's canonical profile data into a specific form's fields.
 * fieldDataJson is a TEXT column containing JSON: { fieldKey: value } pairs.
 * This is a reference data store — it does NOT generate official government forms.
 * completeness is 0-100, computed from non-null key fields at save time.
 */
@Entity
@Table(name = "imm_form_instances",
       uniqueConstraints = @UniqueConstraint(columnNames = {"case_id", "form_type"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FormInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "case_id", nullable = false)
    private ImmigrationCase immigrationCase;

    // Force a plain VARCHAR column. Without @JdbcTypeCode, Hibernate 6 + MySQLDialect maps a
    // String enum to a native MySQL ENUM(...) column, and ddl-auto=update never updates the
    // enum value list when new constants are added → "Data truncated for column" on insert.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "form_type", nullable = false, length = 50)
    private FormType formType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 30)
    private FormStatus status = FormStatus.DRAFT;

    // JSON field map for the form — stored as TEXT for H2 + MySQL compatibility
    @Column(name = "field_data_json", columnDefinition = "TEXT")
    private String fieldDataJson;

    // 0–100; computed from non-null key fields at save time
    @Column(name = "completeness")
    private int completeness;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist  protected void onCreate() { createdAt = updatedAt = LocalDateTime.now(); }
    @PreUpdate   protected void onUpdate()  { updatedAt = LocalDateTime.now(); }
}
