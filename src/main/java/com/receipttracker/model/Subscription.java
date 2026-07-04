package com.receipttracker.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "subscriptions")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "square_subscription_id", unique = true)
    private String squareSubscriptionId;

    @Column(name = "square_customer_id", nullable = false)
    private String squareCustomerId;

    // loose refs to users.id / organizations.id — no JPA relation, per cross-feature FK rule
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "org_id")
    private Long orgId;

    @Column(name = "plan_id", nullable = false)
    private String planId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubscriptionStatus status = SubscriptionStatus.ACTIVE;

    @Column(name = "current_period_end")
    private LocalDateTime currentPeriodEnd;

    /** Set when status flips to PAST_DUE; cleared when it returns to ACTIVE or is CANCELED. */
    @Column(name = "grace_period_ends_at")
    private LocalDateTime gracePeriodEndsAt;

    /** Raw status string from the last Square webhook/API response seen for this subscription. */
    @Column(name = "last_payment_status")
    private String lastPaymentStatus;

    /**
     * Set when the user requests self-service cancellation. Square schedules cancellation for
     * the end of the current billing period rather than canceling immediately, so status/feature
     * access are left untouched until then — the existing subscription.canceled webhook flips
     * status to CANCELED and revokes access once the period actually ends.
     */
    @Column(name = "cancel_effective_date")
    private LocalDate cancelEffectiveDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
