package com.receipttracker.immigration.dto;

import java.time.LocalDate;

public record LotteryResultRequest(Boolean selectedInLottery, LocalDate selectionDate) {}
