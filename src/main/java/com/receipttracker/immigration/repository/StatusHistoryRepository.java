package com.receipttracker.immigration.repository;

import com.receipttracker.immigration.model.ImmigrationCase;
import com.receipttracker.immigration.model.StatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StatusHistoryRepository extends JpaRepository<StatusHistory, Long> {

    List<StatusHistory> findByImmigrationCaseOrderByChangedAtDesc(ImmigrationCase immigrationCase);
}
