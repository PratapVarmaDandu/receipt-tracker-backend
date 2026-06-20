package com.receipttracker.immigration.dto;

public record GeneratePdfRequest(boolean overridePendingReview) {
    public GeneratePdfRequest() { this(false); }
}
