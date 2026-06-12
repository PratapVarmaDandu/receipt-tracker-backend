package com.receipttracker.dto;

import lombok.Data;

@Data
public class PlatformSquareConfigRequest {
    private String accessToken;
    private String applicationId;
    private String locationId;
    private String webhookSignatureKey;
    private String environment;
    private String planIdGarage;
    private String planIdVault;
    private String planIdJobs;
    private String planIdSuite;
}
