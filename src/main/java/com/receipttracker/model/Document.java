package com.receipttracker.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "documents")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Display name shown to the user (not used for file path). */
    @Column(nullable = false)
    private String title;

    /** Original filename as provided by the user — stored for display only. */
    private String originalFileName;

    /** UUID-based filename in storage — safe against path traversal. */
    @Column(nullable = false)
    private String storedFileName;

    /** MIME type validated on upload. */
    private String contentType;

    /** File size in bytes. */
    private Long fileSize;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentCategory category;

    /**
     * Free-form subcategory string (e.g. "W2", "H1B_APPROVAL", "1040").
     * Valid values are defined in DocumentSubcategories.
     */
    private String subcategory;

    /** Relevant year: tax year for TAX docs, visa year for IMMIGRATION, etc. */
    private Integer documentYear;

    /** Null if the document does not expire. */
    private LocalDate expiryDate;

    /** Free-form notes or description. */
    @Column(columnDefinition = "TEXT")
    private String notes;

    /** Soft-delete / hide from main view. */
    private boolean archived = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @OneToMany(mappedBy = "document", cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
    private List<DocumentNextStep> nextSteps = new ArrayList<>();

    private LocalDateTime uploadedAt;
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        uploadedAt = LocalDateTime.now();
        updatedAt  = LocalDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
