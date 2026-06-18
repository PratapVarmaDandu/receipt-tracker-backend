package com.receipttracker.immigration.dto;

public record EmployerOnboardRequest(
        String orgName,
        String contactName,
        String contactEmail,
        String address,
        String city,
        String stateCode,
        String zipCode,
        String einNumber,
        String website
) {}
