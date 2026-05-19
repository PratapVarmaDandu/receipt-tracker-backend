package com.receipttracker.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class CashbackSuggestionDTO {
    private String category;
    private String displayCategory;
    private String currentCard;
    private String currentCashbackRate;
    private String recommendedCard;
    private String recommendedCashbackRate;
    private BigDecimal monthlySpending;
    private BigDecimal additionalMonthlyEarning;
    private BigDecimal annualSavings;
    private String reason;
    private String cardApplyUrl;
}
