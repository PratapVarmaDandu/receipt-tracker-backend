package com.receipttracker.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "org_features",
       uniqueConstraints = @UniqueConstraint(columnNames = {"org_id", "feature"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrgFeature {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "org_id", nullable = false)
    private Organization org;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AppFeature feature;

    @Column(name = "granted_at")
    private LocalDateTime grantedAt;

    /** Null = no expiry (perpetual grant). */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @PrePersist
    protected void onCreate() {
        grantedAt = LocalDateTime.now();
    }

    public boolean isActive() {
        return expiresAt == null || expiresAt.isAfter(LocalDateTime.now());
    }
}
