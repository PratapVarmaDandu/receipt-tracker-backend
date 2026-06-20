package com.receipttracker.immigration.dto;

import java.time.LocalDate;

public record UpdateRfeRequest(
        LocalDate issuedDate,
        LocalDate responseDeadline,
        String uscisCategory,
        String uscisNote,
        String status
) {}
