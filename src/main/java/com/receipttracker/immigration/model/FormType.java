package com.receipttracker.immigration.model;

public enum FormType {
    // TODO: verify form titles against official USCIS/DOS documentation before displaying to users
    I129("I-129 Petition for Nonimmigrant Worker"),
    DS160("DS-160 Online Nonimmigrant Visa Application");

    public final String displayName;
    FormType(String displayName) { this.displayName = displayName; }
}
