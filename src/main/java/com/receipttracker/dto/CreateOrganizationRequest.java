package com.receipttracker.dto;

import lombok.Data;

@Data
public class CreateOrganizationRequest {
    private String name;
    private String slug;
}
