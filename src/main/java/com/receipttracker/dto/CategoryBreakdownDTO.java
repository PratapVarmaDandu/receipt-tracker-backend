package com.receipttracker.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class CategoryBreakdownDTO {
    private String category;
    private String displayName;
    private BigDecimal amount;
    private double percentage;
    private int transactionCount;
    private String bestCard;
    private String bestCashbackRate;
}
