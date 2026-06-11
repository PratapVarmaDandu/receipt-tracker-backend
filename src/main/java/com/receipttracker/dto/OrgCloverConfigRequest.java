package com.receipttracker.dto;

import lombok.Data;

@Data
public class OrgCloverConfigRequest {
    private String accessToken;
    private String merchantId;
    private String publicKey;   // PAK for the browser SDK
    private String environment;
}
