package com.receipttracker.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "vehicle_access",
       uniqueConstraints = @UniqueConstraint(columnNames = {"vehicle_id", "invitee_email"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VehicleAccess {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    /** Null until the invitee accepts the invite and logs in. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "invitee_email", nullable = false)
    private String inviteeEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccessStatus status = AccessStatus.PENDING;

    @Column(name = "invite_token", unique = true)
    private String inviteToken;

    @Column(name = "granted_at")
    private LocalDateTime grantedAt;

    @PrePersist
    protected void onCreate() {
        inviteToken = UUID.randomUUID().toString();
        grantedAt = LocalDateTime.now();
    }

    public enum AccessStatus { PENDING, ACCEPTED, REVOKED }
}
