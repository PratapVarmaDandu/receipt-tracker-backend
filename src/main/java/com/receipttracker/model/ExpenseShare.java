package com.receipttracker.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "expense_shares")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExpenseShare {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receipt_id", nullable = false)
    private Receipt receipt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inviter_id", nullable = false)
    private User inviter;

    @Column(nullable = false)
    private String inviteeEmail;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invitee_id")
    private User invitee;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal shareAmount;

    @Column(length = 500)
    private String shareNote;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ShareStatus status = ShareStatus.PENDING;

    @Column(unique = true, nullable = false)
    private String inviteToken;

    @Column(precision = 10, scale = 2)
    private BigDecimal counterAmount;

    @Column(length = 500)
    private String counterNote;

    @Column(length = 500)
    private String changeResponseNote;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // populated for ITEM_BASED shares; empty for EQUAL/CUSTOM
    @OneToMany(mappedBy = "share", cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
    private List<ExpenseShareItem> items = new ArrayList<>();

    private String splitType;

    /** True when the receipt owner owes the invitee (invitee paid the bill). */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean paidForOwner = false;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (inviteToken == null) {
            inviteToken = UUID.randomUUID().toString().replace("-", "");
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
