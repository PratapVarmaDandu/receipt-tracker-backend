package com.receipttracker.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "google_id", unique = true, nullable = false)
    private String googleId;

    @Column(unique = true, nullable = false)
    private String email;

    private String name;

    @Column(length = 512)
    private String picture;

    private LocalDateTime createdAt;

    // ── Storage configuration ──────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "storage_type")
    private StorageType storageType = StorageType.LOCAL;

    @Column(name = "local_storage_path", length = 1024)
    private String localStoragePath;

    @Column(name = "s3_bucket_name")
    private String s3BucketName;

    @Column(name = "s3_region")
    private String s3Region;

    /** AES-256-GCM encrypted access key ID */
    @Column(name = "s3_access_key_id", length = 512)
    private String s3AccessKeyId;

    /** AES-256-GCM encrypted secret access key */
    @Column(name = "s3_secret_access_key", length = 1024)
    private String s3SecretAccessKey;

    @Column(name = "storage_configured")
    private boolean storageConfigured = false;

    /** True once the user has dismissed the first-login welcome / privacy banner. */
    @Column(name = "welcome_dismissed")
    private boolean welcomeDismissed = false;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
