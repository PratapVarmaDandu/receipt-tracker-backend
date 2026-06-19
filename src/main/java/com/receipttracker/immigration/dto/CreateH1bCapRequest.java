package com.receipttracker.immigration.dto;

import java.time.LocalDate;

public record CreateH1bCapRequest(int registrationYear, String registrationNumber, LocalDate registrationDate) {}
