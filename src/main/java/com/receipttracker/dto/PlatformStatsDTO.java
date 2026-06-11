package com.receipttracker.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlatformStatsDTO {
    private long totalOrgs;
    private long activeOrgs;
    private long suspendedOrgs;
    private long freeOrgs;
    private long proOrgs;
    private long totalMembers;
    private long squareConfiguredOrgs;
}
