package com.receipttracker.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrgSquareConfigDTO {
    private String applicationId;
    private String locationId;
    private String environment;
    private boolean configured;
}
