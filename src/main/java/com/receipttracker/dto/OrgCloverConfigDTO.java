package com.receipttracker.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrgCloverConfigDTO {
    private String merchantId;
    private String publicKey;   // PAK — safe to return; used by browser SDK
    private String environment;
    private boolean configured;
}
