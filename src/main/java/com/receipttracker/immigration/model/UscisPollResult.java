package com.receipttracker.immigration.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "imm_uscis_poll_results")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UscisPollResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // loose ref to imm_cases.id — no JPA FK per cross-feature FK rule
    @Column(name = "case_id", nullable = false)
    private Long caseId;

    @Column(name = "polled_at", nullable = false)
    private LocalDateTime polledAt;

    @Column(name = "raw_status_text", columnDefinition = "TEXT")
    private String rawStatusText;

    @Column(name = "detected_status", length = 50)
    private String detectedStatus;

    @Column(name = "status_changed", nullable = false)
    private boolean statusChanged = false;
}
