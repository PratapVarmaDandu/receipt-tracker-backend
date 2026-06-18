package com.receipttracker.immigration.dto;

public record PartnershipJoinInfoDTO(
        Long partnershipId,
        Long lawFirmOrgId,
        String lawFirmName,
        String inviteEmail,
        String status
) {}
