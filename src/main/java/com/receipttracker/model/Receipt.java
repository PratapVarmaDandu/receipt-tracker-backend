package com.receipttracker.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "receipts")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Receipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String storeName;

    @Enumerated(EnumType.STRING)
    private StoreType storeType;

    @Enumerated(EnumType.STRING)
    private ReceiptType receiptType = ReceiptType.PURCHASE;

    private LocalDateTime purchaseDateTime;

    private String cardType;        // VISA, MASTERCARD, AMEX, DISCOVER
    private String cardBank;        // CHASE, DISCOVER, AMEX, CAPITAL_ONE, CITI, etc.
    private String lastFourDigits;
    private String paymentCard;     // e.g. CHASE_VISA_1234

    private BigDecimal subtotal;
    private BigDecimal tax;
    private BigDecimal tip;
    private BigDecimal total;

    @Column(columnDefinition = "TEXT")
    private String rawOcrText;

    private String imageFileName;
    private String imageFilePath;

    private LocalDateTime uploadedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id")
    private ExpenseGroup group;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id")
    private Vehicle vehicle;

    /** Vehicle expense category: FUEL, MAINTENANCE, REPAIR, INSURANCE, REGISTRATION, PARKING, WASH, OTHER */
    private String vehicleCategory;

    @OneToMany(mappedBy = "receipt", cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
    private List<ReceiptItem> items = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        uploadedAt = LocalDateTime.now();
    }
}
