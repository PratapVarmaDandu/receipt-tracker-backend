package com.receipttracker.immigration.repository;

import com.receipttracker.immigration.model.CaseEvent;
import com.receipttracker.immigration.model.ImmigrationCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CaseEventRepository extends JpaRepository<CaseEvent, Long> {
    List<CaseEvent> findByImmigrationCaseOrderByEventDateDesc(ImmigrationCase immigrationCase);
}
