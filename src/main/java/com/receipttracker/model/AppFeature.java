package com.receipttracker.model;

/**
 * Sellable product modules. CORE (receipts, groups, cashback, analytics)
 * is always available and intentionally has no entry here.
 */
public enum AppFeature {
    EXPENSE_SHARING,
    DOCUMENT_VAULT,
    GARAGE,
    JOB_TRACKER,
    SHOP_POS,
    VISA_TRACKER
}
