package com.receipttracker.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
public class AnalyticsDTO {
    private BigDecimal totalSpending;
    private BigDecimal totalCashbackEarned;
    private BigDecimal totalPotentialCashback;
    private BigDecimal cashbackLeftOnTable;

    private Map<String, BigDecimal> spendingByCategory;
    private Map<String, BigDecimal> spendingByCard;
    private Map<String, BigDecimal> cashbackByCard;
    private Map<String, BigDecimal> spendingByMonth;
    private Map<String, Map<String, BigDecimal>> spendingByCategoryPerMonth;

    private List<CashbackSuggestionDTO> suggestions;
    private List<CategoryBreakdownDTO> categoryBreakdown;

    private int totalReceipts;
    private BigDecimal avgReceiptValue;
}
