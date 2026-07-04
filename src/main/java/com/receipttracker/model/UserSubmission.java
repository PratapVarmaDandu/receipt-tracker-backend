package com.receipttracker.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_submissions")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubmissionType type;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String message;

    /** UUID-based filename in storage — safe against path traversal. Null if no attachment. */
    private String attachmentPath;

    private String attachmentMimeType;

    /** Auto-captured client diagnostic bundle (console errors/warnings, recent failed calls), serialized JSON. */
    @Column(columnDefinition = "TEXT")
    private String clientLogJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubmissionStatus status = SubmissionStatus.NEW;

    /** Set true when a platform admin grants a bonus-month reward for this submission (any type). */
    private boolean rewardGranted = false;

    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
