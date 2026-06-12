package com.receipttracker.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlatformSquareConfigDTO {
    private boolean configured;
    private String applicationId;
    private String locationId;
    private String environment;
    private String planIdGarage;
    private String planIdVault;
    private String planIdJobs;
    private String planIdSuite;
}
