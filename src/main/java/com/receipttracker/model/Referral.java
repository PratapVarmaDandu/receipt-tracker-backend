package com.receipttracker.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A completed referral: {referredUser} signed in for the first time after following
 * {referrer}'s referral link. Rows are only ever created at claim time (login has
 * already happened), so there is no PENDING stage to track — every row represents a
 * genuine completed signup.
 */
@Entity
@Table(name = "referrals",
       uniqueConstraints = @UniqueConstraint(columnNames = "referred_user_id"))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Referral {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "referrer_id", nullable = false)
    private User referrer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "referred_user_id", nullable = false)
    private User referredUser;

    /** False when the referrer's annual reward cap was already reached at claim time. */
    @Column(name = "reward_granted")
    private boolean rewardGranted = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
