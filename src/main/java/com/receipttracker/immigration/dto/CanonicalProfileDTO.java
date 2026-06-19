package com.receipttracker.immigration.dto;

import java.time.LocalDateTime;

/**
 * passports: list of { id, number (decrypted), country, issueDate, expiryDate, notes, documentIds[] }
 * travelEntries: list of { id, portOfEntry, i94Number, entryDate, admittedUntil, visaClass, notes, documentIds[] }
 * JSON sub-fields deserialized to Object so Jackson writes them as native JSON in the HTTP response.
 *
 * Sensitive encrypted fields (SSN, A-Number, I-94 enc, EAD card number) are NEVER returned in this DTO.
 * Only boolean presence flags (hasSsn, hasAlienNumber, etc.) are exposed so the UI can show
 * "already set" without leaking the values. Decryption happens only at PDF generation time.
 *
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

        // Sensitive field presence flags — raw values never returned in API responses
        boolean hasSsn,
        boolean hasAlienNumber,
        boolean hasI94NumberEnc,
        boolean hasEadCardNumber,

        // EAD (non-sensitive)
        String eadCategory,
        String eadExpiryDate,
        String eadCaseNumber,

        // Notification preferences
        boolean notificationEmailEnabled,
        boolean notificationSmsEnabled,
        String notificationPhone,

        // USCIS / profile preferences
        String uscisOnlineAccountNumber,
        String preferredLanguage,

        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
