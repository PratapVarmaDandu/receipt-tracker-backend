package com.receipttracker.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "vehicles")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Vehicle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── Core identity ─────────────────────────────────────────────────────────

    @Column(nullable = false)
    private String make;

    @Column(nullable = false)
    private String model;

    @Column(nullable = false)
    private Integer modelYear;

    private String trim;
    private String vin;
    private String color;

    // ── Registration ──────────────────────────────────────────────────────────

    private String licensePlate;

    /** US state abbreviation for registration, e.g. "CA", "TX". */
    private String registrationState;

    private LocalDate tagExpirationDate;

    // ── Insurance ─────────────────────────────────────────────────────────────

    private String insuranceProvider;
    private String insurancePolicyNumber;
    private LocalDate insuranceExpiryDate;

    // ── Purchase info ─────────────────────────────────────────────────────────

    private LocalDate purchaseDate;

    @Column(precision = 12, scale = 2)
    private BigDecimal purchasePrice;

    private boolean purchasedFromDealer = false;
    private String dealerName;

    // ── Current state ─────────────────────────────────────────────────────────

    /** Odometer reading (miles). */
    private Integer currentMileage;

    @Column(columnDefinition = "TEXT")
    private String notes;

    // ── Photos (stored as UUID filenames in vehicle photo directory) ──────────

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "vehicle_photos", joinColumns = @JoinColumn(name = "vehicle_id"))
    @Column(name = "photo_filename")
    private List<String> photoFilenames = new ArrayList<>();

    // ── Owner ─────────────────────────────────────────────────────────────────

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
