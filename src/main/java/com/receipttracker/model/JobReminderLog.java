package com.receipttracker.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One row per (job application, day) a follow-up push reminder was sent — prevents
 * duplicate sends if the scheduler runs more than once or restarts mid-day. Loose
 * jobApplicationId (plain Long, no JPA relation) — same cross-feature-FK-free pattern
 * as KeyDateReminder / UscisPollResult.
 */
@Entity
@Table(name = "job_reminder_logs", uniqueConstraints = @UniqueConstraint(columnNames = {"job_application_id", "reminded_date"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class JobReminderLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_application_id", nullable = false)
    private Long jobApplicationId;

    @Column(name = "reminded_date", nullable = false)
    private LocalDate remindedDate;

    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
