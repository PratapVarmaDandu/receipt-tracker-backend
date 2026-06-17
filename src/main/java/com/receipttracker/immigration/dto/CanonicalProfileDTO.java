package com.receipttracker.immigration.dto;

import java.time.LocalDateTime;

/**
 * passports: list of { id, number (decrypted), country, issueDate, expiryDate, notes, documentIds[] }
 * travelEntries: list of { id, portOfEntry, i94Number, entryDate, admittedUntil, visaClass, notes, documentIds[] }
 * JSON sub-fields deserialized to Object so Jackson writes them as native JSON in the HTTP response.
 * Never log this record — passports contain decrypted passport numbers.
 */
public record CanonicalProfileDTO(
        Long id,
        Long beneficiaryId,

        // Bio
        String legalFirstName,
        String legalLastName,
        String middleName,
        String dateOfBirth,
        String countryOfBirth,
        String citizenshipCountry,
        String gender,

        // Passport list (numbers decrypted; current = highest issueDate)
        Object passports,

        // Travel entry list (I-94 history)
        Object travelEntries,

        // Current visa status — standalone fields used by FormMappingService
        String currentVisaType,
        String currentVisaExpiry,

        // Contact
        String phone,

        // JSON sub-fields returned as native JSON objects/arrays
        Object currentAddress,
        Object education,
        Object employment,
        Object dependents,
        Object priorVisas,

        String notes,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
