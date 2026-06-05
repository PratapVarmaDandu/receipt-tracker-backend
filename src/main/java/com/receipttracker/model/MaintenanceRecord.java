package com.receipttracker.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "maintenance_records")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MaintenanceRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MaintenanceType maintenanceType;

    /** Used when maintenanceType == OTHER or for more specific labeling. */
    private String customDescription;

    @Column(nullable = false)
    private LocalDate serviceDate;

    private Integer mileage;

    @Column(precision = 10, scale = 2)
    private BigDecimal cost;

    private String provider;     // shop name / DIY

    @Column(columnDefinition = "TEXT")
    private String notes;

    /**
     * Optional link to an existing Receipt in the receipt_tracker system.
     * Nullable — not all maintenance records will have a scanned receipt.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linked_receipt_id")
    private Receipt linkedReceipt;

    /** Stored filename for an uploaded receipt image / PDF for this service. */
    private String receiptFileName;

    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() { createdAt = LocalDateTime.now(); }
}
