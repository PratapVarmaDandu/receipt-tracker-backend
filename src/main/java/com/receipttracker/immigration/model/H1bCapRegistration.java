package com.receipttracker.immigration.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * H-1B cap registration details for a case.
 * One registration per case per year (case_id is unique).
 */
@Entity
@Table(name = "imm_h1b_cap_registrations")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class H1bCapRegistration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "case_id", nullable = false, unique = true)
    private ImmigrationCase immigrationCase;

    @Column(name = "registration_year", nullable = false)
    private int registrationYear;

    // USCIS-assigned registration confirmation number
    @Column(name = "registration_number", length = 100)
    private String registrationNumber;

    // null until lottery results are published
    @Column(name = "selected_in_lottery")
    private Boolean selectedInLottery;

    @Column(name = "selection_date")
    private LocalDate selectionDate;

    @Column(name = "registration_date", nullable = false)
    private LocalDate registrationDate;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
