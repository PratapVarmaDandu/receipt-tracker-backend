package com.receipttracker.dto;

import lombok.Data;

@Data
public class SubscriptionRequest {
    private String sourceId;
    private String planId;
}
