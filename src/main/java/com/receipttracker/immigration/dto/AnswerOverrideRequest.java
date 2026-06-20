package com.receipttracker.immigration.dto;

public record AnswerOverrideRequest(
        String value,
        String overrideReason
) {}
