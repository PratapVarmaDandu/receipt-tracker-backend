package com.receipttracker.repository;

import com.receipttracker.model.DocumentNextStep;
import com.receipttracker.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

public interface DocumentNextStepRepository extends JpaRepository<DocumentNextStep, Long> {

    /** All pending next steps across all documents for a user, ordered by due date. */
    @Query("SELECT ns FROM DocumentNextStep ns WHERE ns.document.user = :user " +
           "AND ns.completed = false ORDER BY ns.dueDate ASC NULLS LAST")
    List<DocumentNextStep> findPendingByUser(User user);

    /** Pending steps due on or before a given date (for dashboard urgency widget). */
    @Query("SELECT ns FROM DocumentNextStep ns WHERE ns.document.user = :user " +
           "AND ns.completed = false AND ns.dueDate <= :cutoff ORDER BY ns.dueDate ASC")
    List<DocumentNextStep> findDueBefore(User user, LocalDate cutoff);
}
