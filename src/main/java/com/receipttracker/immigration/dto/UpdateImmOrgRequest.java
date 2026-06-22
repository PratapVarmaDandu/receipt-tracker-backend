package com.receipttracker.immigration.dto;

/** Patch-semantics: null fields are not applied. */
public record UpdateImmOrgRequest(
        String name,
        String contactName,
        String contactEmail,
        String address,
        String city,
        String stateCode,
        String zipCode,
        String einNumber,
        String website
) {}
