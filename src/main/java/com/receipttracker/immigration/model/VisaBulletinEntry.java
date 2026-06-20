package com.receipttracker.immigration.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "imm_visa_bulletin_entries",
    uniqueConstraints = @UniqueConstraint(
        columnNames = {"bulletin_year", "bulletin_month", "preference_category", "country_of_chargeability"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VisaBulletinEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bulletin_year", nullable = false)
    private Integer bulletinYear;

    @Column(name = "bulletin_month", nullable = false)
    private Integer bulletinMonth;

    // EB1 | EB2 | EB3 | EB4 | EB5
    @Column(name = "preference_category", length = 20, nullable = false)
    private String preferenceCategory;

    // INDIA | CHINA | PHILIPPINES | MEXICO | ALL_OTHER
    @Column(name = "country_of_chargeability", length = 100, nullable = false)
    private String countryOfChargeability;

    // null = C (current / immediately available)
    @Column(name = "final_action_date")
    private LocalDate finalActionDate;

    // null = unavailable (bulletin hasn't opened this table for the month)
    @Column(name = "dates_for_filing")
    private LocalDate datesForFiling;

    @Column(name = "scraped_at", nullable = false)
    private LocalDateTime scrapedAt;

    @PrePersist
    protected void onCreate() {
        if (scrapedAt == null) scrapedAt = LocalDateTime.now();
    }
}
