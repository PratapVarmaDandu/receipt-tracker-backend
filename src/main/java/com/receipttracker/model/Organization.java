package com.receipttracker.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

@Entity
@Table(name = "organizations")
@Data
@NoArgsConstructor
@AllArgsConstructor
@DynamicUpdate
public class Organization {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(unique = true, nullable = false)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrgPlan plan = OrgPlan.FREE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrgStatus status = OrgStatus.ACTIVE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    // ── Per-org Square credentials (token AES-256-GCM encrypted) ──────────────

    @Column(name = "square_access_token_enc", length = 1024)
    private String squareAccessTokenEnc;

    @Column(name = "square_application_id")
    private String squareApplicationId;

    @Column(name = "square_location_id")
    private String squareLocationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "square_environment")
    private SquareEnv squareEnvironment = SquareEnv.SANDBOX;

    // ── Per-org Clover credentials (token AES-256-GCM encrypted) ─────────────

    @Column(name = "clover_access_token_enc", length = 1024)
    private String cloverAccessTokenEnc;

    @Column(name = "clover_merchant_id")
    private String cloverMerchantId;

    // Public API Key (PAK) — used by the browser SDK; not secret, stored plain
    @Column(name = "clover_public_key")
    private String cloverPublicKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "clover_environment")
    private CloverEnv cloverEnvironment = CloverEnv.SANDBOX;

    @Column(name = "public_store", columnDefinition = "BOOLEAN DEFAULT FALSE")
    private boolean publicStore = false;

    // nullable — existing orgs stay null; EMPLOYER or LAW_FIRM for immigration-module orgs
    @Enumerated(EnumType.STRING)
    @Column(name = "org_type")
    private OrgType orgType;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public boolean isSquareConfigured() {
        return squareAccessTokenEnc != null && !squareAccessTokenEnc.isBlank();
    }

    public boolean isCloverConfigured() {
        return cloverAccessTokenEnc != null && !cloverAccessTokenEnc.isBlank();
    }

    public enum OrgPlan    { FREE, PRO }
    public enum OrgStatus  { ACTIVE, SUSPENDED }
    public enum SquareEnv  { SANDBOX, PRODUCTION }
    public enum CloverEnv  { SANDBOX, PRODUCTION }
    public enum OrgType    { EMPLOYER, LAW_FIRM }
}
