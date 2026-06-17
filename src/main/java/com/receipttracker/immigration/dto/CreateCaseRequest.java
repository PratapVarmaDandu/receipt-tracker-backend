package com.receipttracker.immigration.dto;

public record CreateCaseRequest(
        String caseType,

        // Required when called by an employer or law firm member
        String beneficiaryEmail,

        Long employerImmOrgId,   // nullable — which employer org is sponsoring
        Long lawFirmImmOrgId,    // nullable — which law firm is handling

        // Optional: link to a parent H1B case (required for H4 / H4_EAD)
        Long parentCaseId,

        // Optional: assign a specific attorney (ImmOrgMember id) within the law firm
        Long assignedAttorneyMemberId
) {}
