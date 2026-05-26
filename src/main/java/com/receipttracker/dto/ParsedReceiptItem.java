package com.receipttracker.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ParsedReceiptItem {
    private String name;
    private String description;
    private Integer quantity;
    private BigDecimal unitPrice;
    private BigDecimal totalPrice;
    private String category;
}
