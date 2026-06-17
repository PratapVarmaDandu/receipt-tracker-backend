package com.receipttracker.immigration.dto;

public record OrgPartnershipDTO(
        Long id,
        Long employerOrgId, String employerOrgName,
        Long lawFirmOrgId,  String lawFirmOrgName,
        String status, String createdAt
) {}
