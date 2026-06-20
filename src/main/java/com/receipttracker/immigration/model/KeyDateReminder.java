package com.receipttracker.immigration.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "imm_key_date_reminders",
       uniqueConstraints = @UniqueConstraint(columnNames = {"key_date_id", "days_before_date", "recipient_email"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KeyDateReminder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "key_date_id", nullable = false)
    private KeyDate keyDate;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;

    @Column(name = "days_before_date", nullable = false)
    private int daysBeforeDate;

    @Column(name = "recipient_email", nullable = false)
    private String recipientEmail;
}
