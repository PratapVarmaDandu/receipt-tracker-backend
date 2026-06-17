package com.receipttracker.immigration.dto;

/**
 * All date fields are ISO-8601 strings (yyyy-MM-dd); service parses to LocalDate.
 * passports: list of { id?, number (plaintext), country, issueDate, expiryDate, notes, documentIds[] }
 *   — service encrypts each number before persisting.
 * travelEntries: list of { id?, portOfEntry, i94Number, entryDate, admittedUntil, visaClass, notes, documentIds[] }
 * JSON sub-fields (currentAddress, education, …) arrive as deserialized Java objects
 * from the request body; service serializes them to TEXT columns via ObjectMapper.
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

        // JSON sub-fields — Jackson deserializes from request body
        Object currentAddress,
        Object education,
        Object employment,
        Object dependents,
        Object priorVisas,

        String notes
) {}
