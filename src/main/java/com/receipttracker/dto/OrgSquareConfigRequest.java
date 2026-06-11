package com.receipttracker.dto;

import lombok.Data;

@Data
public class OrgSquareConfigRequest {
    private String accessToken;
    private String applicationId;
    private String locationId;
    private String environment; // SANDBOX | PRODUCTION
}
