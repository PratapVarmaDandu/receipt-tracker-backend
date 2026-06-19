package com.receipttracker.immigration.model;

public enum FormType {
    // TODO: verify form titles against official USCIS/DOS documentation before displaying to users
    I129("I-129 Petition for Nonimmigrant Worker"),
    DS160("DS-160 Online Nonimmigrant Visa Application"),
    I485("I-485 Application to Register Permanent Residence"),
    I765("I-765 Application for Employment Authorization"),
    I131("I-131 Application for Travel Document"),
    I140("I-140 Immigrant Petition for Alien Workers"),
    I539("I-539 Application to Extend/Change Nonimmigrant Status"),
    G28("G-28 Notice of Entry of Appearance as Attorney"),
    I290B("I-290B Notice of Appeal or Motion"),
    I693("I-693 Report of Immigration Medical Examination");

    public final String displayName;
    FormType(String displayName) { this.displayName = displayName; }
}
