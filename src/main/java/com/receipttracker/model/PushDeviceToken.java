package com.receipttracker.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "push_device_tokens", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "token"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PushDeviceToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 512)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PushPlatform platform;

    private LocalDateTime createdAt;
    private LocalDateTime lastSeenAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        lastSeenAt = LocalDateTime.now();
    }
}
