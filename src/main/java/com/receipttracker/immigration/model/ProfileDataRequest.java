package com.receipttracker.immigration.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "imm_profile_data_requests")
@Data @NoArgsConstructor @AllArgsConstructor
public class ProfileDataRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_id", nullable = false)
    private Long caseId; // loose ref — cross-feature FK rule

    @Column(name = "requested_by_user_id", nullable = false)
    private Long requestedByUserId; // loose ref

    @Column(name = "target_relationship", length = 50, nullable = false)
    private String targetRelationship; // BENEFICIARY | EMPLOYER

    @Column(name = "token", unique = true, nullable = false)
    private String token;

    @Column(name = "sections_requested", columnDefinition = "TEXT", nullable = false)
    private String sectionsRequested; // JSON array e.g. ["personalInfo","passportI94"]

    @Column(name = "status", length = 50, nullable = false)
    private String status = "PENDING"; // PENDING | SUBMITTED | EXPIRED

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (token == null) token = UUID.randomUUID().toString();
    }
}
