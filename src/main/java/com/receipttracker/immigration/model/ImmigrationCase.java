package com.receipttracker.immigration.model;

import com.receipttracker.model.User;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "imm_cases")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImmigrationCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_number", unique = true, nullable = false)
    private String caseNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "beneficiary_id", nullable = false)
    private Beneficiary beneficiary;

    // loose refs to imm_orgs.id — no FK per cross-feature FK rule
    @Column(name = "employer_imm_org_id")
    private Long employerImmOrgId;

    @Column(name = "law_firm_imm_org_id")
    private Long lawFirmImmOrgId;

    @Enumerated(EnumType.STRING)
    @Column(name = "case_type", nullable = false)
    private CaseType caseType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private CaseStatus status = CaseStatus.PROSPECTIVE;

    @Column(name = "priority_date")
    private LocalDate priorityDate;

    // USCIS receipt number (e.g. WAC-24-001-00001)
    @Column(name = "receipt_number")
    private String receiptNumber;

    // Dependent case support: H4/H4-EAD cases link to their H1B primary case
    @Column(name = "parent_case_id")
    private Long parentCaseId;

    // I-140 approval flag — set automatically when an I140_EB2/I140_EB3 case reaches PETITION_APPROVED
    // Unlocks H4-EAD eligibility and AC21 portability for linked cases
    @Column(name = "i140_approved", nullable = false)
    private boolean i140Approved = false;

    @Column(name = "i140_approved_date")
    private LocalDate i140ApprovedDate;

    // Which ImmOrgMember (attorney) within the law firm is assigned to this case
    @Column(name = "assigned_attorney_member_id")
    private Long assignedAttorneyMemberId;

    // Which ImmOrgMember (paralegal) within the law firm is assisting this case
    @Column(name = "assigned_paralegal_member_id")
    private Long assignedParalegalMemberId;

    // Beneficiary invite — set when an org member creates a case for an employee
    @Column(name = "beneficiary_invite_token", unique = true)
    private String beneficiaryInviteToken;

    @Column(name = "beneficiary_invite_email")
    private String beneficiaryInviteEmail;

    @Column(name = "beneficiary_invite_expires_at")
    private LocalDateTime beneficiaryInviteExpiresAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id", nullable = false)
    private User createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
