package com.receipttracker.immigration.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record ImmigrationCaseDTO(
        Long id,
        String caseNumber,
        Long beneficiaryId,
        String beneficiaryName,
        String beneficiaryEmail,
        Long employerImmOrgId,
        String employerImmOrgName,
        Long lawFirmImmOrgId,
        String lawFirmImmOrgName,
        String caseType,
        String status,
        LocalDate priorityDate,
        String receiptNumber,

        // Dependent case link
        Long parentCaseId,

        // I-140 approval — unlocks H4-EAD and AC21 portability
        boolean i140Approved,
        LocalDate i140ApprovedDate,

        // Assigned attorney within the law firm
        Long assignedAttorneyMemberId,
        String assignedAttorneyName,

        // Beneficiary invite status
        boolean beneficiaryInvitePending,  // true = invite sent but not yet accepted

        Long createdById,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String callerRelationship   // BENEFICIARY / ATTORNEY / HR_ADMIN / VIEWER / null
) {}
