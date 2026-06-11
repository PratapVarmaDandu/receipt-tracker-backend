package com.receipttracker.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "org_orders")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrgOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "org_id", nullable = false)
    private Organization org;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "placed_by_id", nullable = false)
    private User placedBy;

    @Column(name = "square_order_id")
    private String squareOrderId;

    @Column(name = "square_payment_id")
    private String squarePaymentId;

    @Column(name = "total_amount", precision = 10, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "location_id")
    private String locationId;

    @Column(name = "store_name")
    private String storeName;

    @Column(name = "receipt_id")
    private Long receiptId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status = OrderStatus.COMPLETED;

    @Column(name = "placed_at")
    private LocalDateTime placedAt;

    @PrePersist
    protected void onCreate() {
        placedAt = LocalDateTime.now();
    }

    public enum OrderStatus { PENDING_PAYMENT, COMPLETED, REFUNDED, CANCELLED }
}
