package com.receipttracker.immigration.repository;

import com.receipttracker.immigration.model.ImmFieldAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** Append-only: no update/delete methods exposed. */
public interface ImmFieldAuditEventRepository extends JpaRepository<ImmFieldAuditEvent, Long> {

    List<ImmFieldAuditEvent> findByCaseIdOrderByCreatedAtDesc(Long caseId);

    @Query("SELECT e FROM ImmFieldAuditEvent e WHERE e.caseId = :caseId AND e.action IN :actions ORDER BY e.createdAt DESC")
    List<ImmFieldAuditEvent> findByCaseIdAndActions(
            @Param("caseId") Long caseId,
            @Param("actions") List<String> actions);
}
