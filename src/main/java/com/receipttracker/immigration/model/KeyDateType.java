package com.receipttracker.immigration.model;

public enum KeyDateType {
    PRIORITY_DATE,
    I140_FILED,
    I140_APPROVED,
    I485_FILED,
    H1B_START_DATE,
    VISA_STAMP_EXPIRY,
    // TODO: verify field against official form instruction (I-94 expiry vs status period)
    I94_EXPIRY,
    PASSPORT_EXPIRY,
    PETITION_DEADLINE,
    EAD_EXPIRY,
    AP_EXPIRY,
    OTHER
}
