package com.receipttracker.immigration.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "imm_i9_records")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class I9Record {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // loose ref to imm_orgs.id — no FK per cross-feature FK rule
    @Column(name = "employer_imm_org_id", nullable = false)
    private Long employerImmOrgId;

    @Column(name = "employee_email", nullable = false)
    private String employeeEmail;

    @Column(name = "employee_name", nullable = false)
    private String employeeName;

    @Column(name = "work_auth_type", length = 100)
    private String workAuthType;

    @Column(name = "document_title")
    private String documentTitle;

    @Column(name = "document_number")
    private String documentNumber;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "verified_at")
    private LocalDate verifiedAt;

    @Column(name = "reverification_due")
    private LocalDate reverificationDue;

    // CURRENT | EXPIRING_SOON | EXPIRED — computed in @PrePersist / @PreUpdate
    @Column(name = "status", length = 50, nullable = false)
    private String status = "CURRENT";

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
        computeStatus();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        computeStatus();
    }

    public void computeStatus() {
        if (expiryDate == null) {
            status = "CURRENT";
        } else if (expiryDate.isBefore(LocalDate.now())) {
            status = "EXPIRED";
        } else if (expiryDate.isBefore(LocalDate.now().plusDays(90))) {
            status = "EXPIRING_SOON";
        } else {
            status = "CURRENT";
        }
    }
}
