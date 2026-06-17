package com.receipttracker.immigration.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "imm_key_dates")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KeyDate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "case_id", nullable = false)
    private ImmigrationCase immigrationCase;

    @Enumerated(EnumType.STRING)
    @Column(name = "date_type", nullable = false)
    private KeyDateType dateType;

    @Column(name = "label")
    private String label; // custom display label override

    @Column(name = "date", nullable = false)
    private LocalDate date;

    // true when derived from profile/case data automatically
    @Column(name = "auto_computed")
    private boolean autoComputed;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { createdAt = LocalDateTime.now(); }
}
