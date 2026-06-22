package com.receipttracker.immigration.dto;

public record ImmOrgDTO(
        Long id, String name, String orgType, Long ownerUserId, String createdAt, Long myMemberId,
        // Employer profile fields (null for LAW_FIRM orgs that don't fill them)
        String contactName, String contactEmail,
        String address, String city, String stateCode, String zipCode,
        String einNumber, String website
) {}
