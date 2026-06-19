package com.receipttracker.immigration.model;

public enum ImmOrgMemberRole {
    OWNER,       // full firm / employer admin
    ATTORNEY,    // full case access; approves PDF packets and form versions
    PARALEGAL,   // data collection, questionnaire mgmt, checklists; no final approval authority
    CASE_VIEWER, // read-only on assigned cases
    MEMBER       // legacy — kept for backward compatibility with existing rows
}
