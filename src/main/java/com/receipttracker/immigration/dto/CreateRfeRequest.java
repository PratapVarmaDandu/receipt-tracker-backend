package com.receipttracker.immigration.dto;

import java.time.LocalDate;

public record CreateRfeRequest(
        LocalDate issuedDate,
        LocalDate responseDeadline,
        String uscisCategory,
        String uscisNote
) {}
