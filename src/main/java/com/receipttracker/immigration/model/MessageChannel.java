package com.receipttracker.immigration.model;

/**
 * Messaging channel isolation.
 * SHARED            — all case participants.
 * ATTORNEY_BENEFICIARY — attorney + paralegal + beneficiary only.
 * ATTORNEY_EMPLOYER    — attorney + paralegal + HR_ADMIN only.
 * ATTORNEY_INTERNAL    — attorney + paralegal only; hidden from beneficiary and HR_ADMIN.
 */
public enum MessageChannel {
    SHARED,
    ATTORNEY_BENEFICIARY,
    ATTORNEY_EMPLOYER,
    ATTORNEY_INTERNAL
}
