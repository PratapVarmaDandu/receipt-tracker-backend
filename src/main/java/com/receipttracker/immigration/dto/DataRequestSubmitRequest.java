package com.receipttracker.immigration.dto;

import java.util.Map;

/**
 * Body for POST /api/immigration/data-requests/{token}/submit.
 * sections keys: personalInfo, passportI94, currentStatus, employment,
 *   familyDependents, eadInfo, notificationPreferences.
 * Each value is a JSON object whose fields mirror UpdateProfileRequest.
 *
 * personalInfo: { legalFirstName, legalLastName, middleName, dateOfBirth,
 *   countryOfBirth, citizenshipCountry, gender, phone, currentAddress }
 * passportI94: { passports: [...], travelEntries: [...] }
 * currentStatus: { currentVisaType, currentVisaExpiry }
 * employment: { employment: [...] }
 * familyDependents: { dependents: [...] }
 * eadInfo: { eadCategory, eadExpiryDate, eadCaseNumber }
 * notificationPreferences: { notificationEmailEnabled, notificationSmsEnabled, notificationPhone }
 */
public record DataRequestSubmitRequest(Map<String, Object> sections) {}
