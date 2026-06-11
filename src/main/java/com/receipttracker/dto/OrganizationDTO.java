package com.receipttracker.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OrganizationDTO {
    private Long id;
    private String name;
    private String slug;
    private String plan;
    private String status;
    private Long ownerId;
    private String ownerName;
    private String ownerEmail;
    private int memberCount;
    private LocalDateTime createdAt;
    private String myRole;
    private boolean squareConfigured;
    private String squareEnvironment;
    private boolean cloverConfigured;
    private String cloverEnvironment;
    private int recentOrderCount;
}
