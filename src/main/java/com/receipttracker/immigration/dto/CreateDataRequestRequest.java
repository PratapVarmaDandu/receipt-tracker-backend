package com.receipttracker.immigration.dto;

import java.util.List;

/**
 * sections: section IDs to include — personalInfo, passportI94, currentStatus,
 *   employment, familyDependents, eadInfo, notificationPreferences
 * expiryDays: link lifetime (1–30 days; defaults to 7 if 0)
 */
public record CreateDataRequestRequest(
        String targetRelationship, // BENEFICIARY | EMPLOYER
        List<String> sections,
        int expiryDays
) {}
