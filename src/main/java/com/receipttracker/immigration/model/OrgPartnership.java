package com.receipttracker.immigration.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "imm_org_partnerships")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrgPartnership {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // loose refs to imm_orgs.id; employerOrgId is null until employer completes onboarding
    @Column(name = "employer_org_id")
    private Long employerOrgId;

    @Column(name = "law_firm_org_id", nullable = false)
    private Long lawFirmOrgId;

    @Column(name = "invite_email")
    private String inviteEmail;

    @Column(name = "invite_token", unique = true)
    private String inviteToken;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 50)
    private OrgPartnershipStatus status;

    // loose ref to users.id
    @Column(name = "initiated_by_user_id")
    private Long initiatedByUserId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
