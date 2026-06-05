package com.receipttracker.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "expense_share_items")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExpenseShareItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "share_id", nullable = false)
    private ExpenseShare share;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receipt_item_id", nullable = false)
    private ReceiptItem receiptItem;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal itemTotal;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal taxAmount;

    // effective rate used (receipt.tax / receipt.subtotal); 0 if not computable
    @Column(nullable = false, precision = 8, scale = 6)
    private BigDecimal taxRate;
}
