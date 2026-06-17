package com.receipttracker.immigration.model;

/**
 * Messaging channel isolation.
 * SHARED — all case participants.
 * ATTORNEY_BENEFICIARY — attorney + beneficiary only.
 * ATTORNEY_EMPLOYER — attorney + HR_ADMIN only.
 */
public enum MessageChannel {
    SHARED,
    ATTORNEY_BENEFICIARY,
    ATTORNEY_EMPLOYER
}
