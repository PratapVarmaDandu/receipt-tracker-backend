package com.receipttracker.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "document_shares")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentShare {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(nullable = false)
    private String recipientEmail;

    private String recipientName;

    /** Short purpose label, e.g. "Tax Filing 2024", "H1B Extension". */
    private String purpose;

    @Column(columnDefinition = "TEXT")
    private String message;

    /** Secure UUID token — exposed in the share link URL. */
    @Column(unique = true, nullable = false)
    private String shareToken;

    /** When the share link stops working. */
    private LocalDateTime expiresAt;

    private LocalDateTime sharedAt;

    /** Flipped to true the first time the recipient opens the link. */
    private boolean accessed = false;

    private LocalDateTime accessedAt;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "document_share_docs",
        joinColumns = @JoinColumn(name = "share_id"),
        inverseJoinColumns = @JoinColumn(name = "document_id")
    )
    private List<Document> documents = new ArrayList<>();

    @PrePersist
    void prePersist() {
        sharedAt = LocalDateTime.now();
        if (shareToken == null) {
            shareToken = UUID.randomUUID().toString().replace("-", "");
        }
    }
}
