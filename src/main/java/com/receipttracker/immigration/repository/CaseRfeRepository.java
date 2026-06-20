package com.receipttracker.immigration.repository;

import com.receipttracker.immigration.model.CaseRfe;
import com.receipttracker.immigration.model.ImmigrationCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CaseRfeRepository extends JpaRepository<CaseRfe, Long> {
    List<CaseRfe> findByImmigrationCaseOrderByCreatedAtDesc(ImmigrationCase immigrationCase);
    Optional<CaseRfe> findFirstByImmigrationCaseAndStatusOrderByCreatedAtDesc(ImmigrationCase immigrationCase, String status);
}
