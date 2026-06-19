package com.receipttracker.immigration.dto;

/**
 * All date fields are ISO-8601 strings (yyyy-MM-dd); service parses to LocalDate.
 * passports: list of { id?, number (plaintext), country, issueDate, expiryDate, notes, documentIds[] }
 *   — service encrypts each number before persisting.
 * travelEntries: list of { id?, portOfEntry, i94Number, entryDate, admittedUntil, visaClass, notes, documentIds[] }
 * JSON sub-fields (currentAddress, education, …) arrive as deserialized Java objects
 * from the request body; service serializes them to TEXT columns via ObjectMapper.
 *
 * Sensitive fields (alienNumber, ssn, i94Number, eadCardNumber) are received in plaintext
 * and immediately encrypted by CanonicalProfileService before any persistence.
 * Never log a request that contains these fields.
 */
public record UpdateProfileRequest(
        // Bio
        String legalFirstName,
        String legalLastName,
        String middleName,
        String dateOfBirth,
        String countryOfBirth,
        String citizenshipCountry,
        String gender,

        // Passport list — each item: { id?, number (plaintext), country, issueDate, expiryDate, notes, documentIds[] }
        Object passports,

        // Travel entry list — each item: { id?, portOfEntry, i94Number, entryDate, admittedUntil, visaClass, notes, documentIds[] }
        Object travelEntries,

        // Current visa status (standalone — not part of travel history)
        String currentVisaType,
        String currentVisaExpiry,

        // Contact
        String phone,

        // Sensitive fields — plaintext on the wire; encrypted before persist; never logged
        String alienNumber,    // A-Number (A-XXXXXXXXX); encrypted to alien_number_enc
        String ssn,            // SSN (NNN-NN-NNNN); encrypted to ssn_enc
        String i94Number,      // I-94 admission number; encrypted to i94_number_enc
        String eadCardNumber,  // EAD card number; encrypted to ead_card_number_enc

        // EAD metadata (non-sensitive)
        String eadCategory,
        String eadExpiryDate,
        String eadCaseNumber,

        // Notification preferences
        Boolean notificationEmailEnabled,
        Boolean notificationSmsEnabled,
        String notificationPhone,

        // USCIS / profile preferences
        String uscisOnlineAccountNumber,
        String preferredLanguage,

        // JSON sub-fields — Jackson deserializes from request body
        Object currentAddress,
        Object education,
        Object employment,
        Object dependents,
        Object priorVisas,

        String notes
) {}
