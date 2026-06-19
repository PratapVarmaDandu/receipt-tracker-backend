package com.receipttracker.immigration.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Attorney professional details linked to an ImmOrgMember.
 * barNumbers is stored as JSON TEXT: [{ state, barNumber, admittedDate }]
 * immOrgMemberId is a loose ref (plain Long) — no JPA FK per cross-feature FK rule.
 */
@Entity
@Table(name = "imm_attorney_profiles")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AttorneyProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // loose ref to imm_org_members.id — unique; one profile per member
    @Column(name = "imm_org_member_id", nullable = false, unique = true)
    private Long immOrgMemberId;

    // JSON TEXT: [{ state: String, barNumber: String, admittedDate: String (yyyy-MM-dd) }]
    @Column(name = "bar_numbers_json", columnDefinition = "TEXT")
    private String barNumbersJson;

    // Relative path / key for the attorney's signature image in object storage
    @Column(name = "signature_image_key", length = 500)
    private String signatureImageKey;

    @Column(name = "bio", columnDefinition = "TEXT")
    private String bio;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist  protected void onCreate() { createdAt = updatedAt = LocalDateTime.now(); }
    @PreUpdate   protected void onUpdate()  { updatedAt = LocalDateTime.now(); }
}
