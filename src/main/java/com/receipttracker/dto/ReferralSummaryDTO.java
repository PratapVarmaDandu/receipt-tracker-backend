package com.receipttracker.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReferralSummaryDTO {
    private String referralCode;
    private String shareLink;
    private long totalReferrals;
    private long rewardedReferrals;
    private int annualCap;
}
