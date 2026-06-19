package com.receipttracker.immigration.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "imm_form_shares")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FormShare {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // loose ref to imm_form_instances.id — no FK per cross-feature FK rule
    @Column(name = "form_instance_id", nullable = false)
    private Long formInstanceId;

    @Column(name = "token", unique = true, nullable = false)
    private String token;

    @Column(name = "recipient_email", nullable = false)
    private String recipientEmail;

    // loose ref to users.id — no FK
    @Column(name = "shared_by_user_id", nullable = false)
    private Long sharedByUserId;

    @Column(name = "recipient_type", nullable = false)
    private String recipientType;   // EMPLOYER | BENEFICIARY

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (token == null) token = java.util.UUID.randomUUID().toString();
    }
}
