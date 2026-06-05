package com.receipttracker.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "fuel_records")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FuelRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    @Column(nullable = false)
    private LocalDate fillDate;

    /** Odometer reading at fill-up (miles). Required for MPG calculation. */
    @Column(nullable = false)
    private Integer odometer;

    /** Gallons pumped. */
    @Column(nullable = false, precision = 8, scale = 3)
    private BigDecimal gallons;

    @Column(precision = 6, scale = 3)
    private BigDecimal pricePerGallon;

    @Column(precision = 10, scale = 2)
    private BigDecimal totalCost;

    @Enumerated(EnumType.STRING)
    private FuelType fuelType = FuelType.REGULAR;

    private boolean fullTank = true;   // partial fill-ups skew MPG

    private String stationName;

    @Column(columnDefinition = "TEXT")
    private String notes;

    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() { createdAt = LocalDateTime.now(); }
}
