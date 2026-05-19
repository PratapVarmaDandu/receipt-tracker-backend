package com.receipttracker.dto;

import com.receipttracker.model.ReceiptType;
import com.receipttracker.model.StoreType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class ReceiptDTO {
    private Long id;
    private String storeName;
    private StoreType storeType;
    private ReceiptType receiptType;
    private LocalDateTime purchaseDateTime;
    private String cardType;
    private String cardBank;
    private String lastFourDigits;
    private String paymentCard;
    private BigDecimal subtotal;
    private BigDecimal tax;
    private BigDecimal tip;
    private BigDecimal total;
    private String imageFileName;
    private LocalDateTime uploadedAt;
    private List<ReceiptItemDTO> items;
    private BigDecimal cashbackEarned;
    private BigDecimal potentialCashback;
    private String bestCard;
    private String bestCardRate;
}
