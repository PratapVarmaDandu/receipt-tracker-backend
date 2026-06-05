package com.receipttracker.repository;

import com.receipttracker.model.Document;
import com.receipttracker.model.DocumentCategory;
import com.receipttracker.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    Page<Document> findByUserAndArchivedFalseOrderByUploadedAtDesc(User user, Pageable pageable);

    List<Document> findByUserAndCategoryAndArchivedFalseOrderByDocumentYearDescUploadedAtDesc(
            User user, DocumentCategory category);

    /** Documents expiring within the next {@code days} days. */
    @Query("SELECT d FROM Document d WHERE d.user = :user AND d.archived = false " +
           "AND d.expiryDate IS NOT NULL AND d.expiryDate BETWEEN :today AND :cutoff " +
           "ORDER BY d.expiryDate ASC")
    List<Document> findExpiringBetween(User user, LocalDate today, LocalDate cutoff);

    /** All non-archived documents for user — used for dashboard summary counts. */
    @Query("SELECT d FROM Document d WHERE d.user = :user AND d.archived = false")
    List<Document> findAllActiveByUser(User user);

    long countByUserAndArchivedFalse(User user);
}
