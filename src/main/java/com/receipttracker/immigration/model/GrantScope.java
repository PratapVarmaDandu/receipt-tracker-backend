package com.receipttracker.immigration.model;

public enum GrantScope {
    READ_CASE,
    WRITE_CASE,
    READ_DOCS,
    WRITE_DOCS,
    READ_FORMS,
    WRITE_FORMS,
    MESSAGING,
    APPROVE_FORMS,      // approve PDF packets and form versions — ATTORNEY + OWNER only
    MANAGE_CHECKLISTS,  // manage evidence checklists — ATTORNEY + PARALEGAL
    MANAGE_TASKS        // create / assign / complete tasks — ATTORNEY + PARALEGAL
}
