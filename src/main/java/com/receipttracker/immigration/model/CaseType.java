package com.receipttracker.immigration.model;

public enum CaseType {
    // H-1B family
    H1B_INITIAL,
    H1B_EXTENSION,
    H1B_TRANSFER,
    H1B_AMENDMENT,

    // H-4 dependents
    H4,           // H-4 status (spouse/children of H-1B holder)
    H4_EAD,       // H-4 EAD work permit — requires approved I-140 on primary H-1B case

    // Green card pathway (employer-sponsored)
    PERM,         // DOL permanent labor certification (EB-2/EB-3)
    I140_EB2,     // EB-2 immigrant petition
    I140_EB3,     // EB-3 immigrant petition

    // Adjustment of status
    I485,         // Adjustment of status — green card application
    GC_EAD,       // Work permit while I-485 pending (combo card)
    GC_RENEWAL,   // Green card renewal (10-year)

    // Citizenship
    NATURALIZATION,

    // Other
    CONSULAR      // Consular processing (visa stamp abroad)
}
