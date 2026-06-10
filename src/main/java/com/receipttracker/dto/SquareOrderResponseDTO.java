package com.receipttracker.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SquareOrderResponseDTO {
    private String squareOrderId;
    private String checkoutUrl;
    private Long receiptId;
    private BigDecimal total;
    private String storeName;
}
