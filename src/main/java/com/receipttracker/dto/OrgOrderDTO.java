package com.receipttracker.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class OrgOrderDTO {
    private Long id;
    private String squareOrderId;
    private String squarePaymentId;
    private BigDecimal totalAmount;
    private String storeName;
    private String locationId;
    private Long receiptId;
    private String status;
    private String placedByName;
    private String placedByEmail;
    private LocalDateTime placedAt;
}
