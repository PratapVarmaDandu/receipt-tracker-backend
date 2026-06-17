package com.receipttracker.immigration.repository;

import com.receipttracker.immigration.model.FeedVisibility;
import com.receipttracker.immigration.model.ImmAuditEvent;
import com.receipttracker.immigration.model.ImmigrationCase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** Append-only: no update/delete methods exposed. */
public interface ImmAuditEventRepository extends JpaRepository<ImmAuditEvent, Long> {

    @Query("SELECT e FROM ImmAuditEvent e WHERE e.immigrationCase = :c AND e.visibility IN :visibilities ORDER BY e.createdAt DESC")
    List<ImmAuditEvent> findVisibleEvents(
            @Param("c") ImmigrationCase c,
            @Param("visibilities") List<FeedVisibility> visibilities);
}
