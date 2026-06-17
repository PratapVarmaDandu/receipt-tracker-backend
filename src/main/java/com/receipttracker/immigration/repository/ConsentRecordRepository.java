package com.receipttracker.immigration.repository;

import com.receipttracker.immigration.model.Beneficiary;
import com.receipttracker.immigration.model.CaseRelationship;
import com.receipttracker.immigration.model.ConsentRecord;
import com.receipttracker.immigration.model.ImmigrationCase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ConsentRecordRepository extends JpaRepository<ConsentRecord, Long> {

    List<ConsentRecord> findByImmigrationCaseOrderByActionAtDesc(ImmigrationCase immigrationCase);

    // Latest consent record for this case + relationship (to determine current consent state)
    @Query("SELECT c FROM ConsentRecord c WHERE c.immigrationCase = :cas AND c.coversRelationship = :rel ORDER BY c.actionAt DESC")
    List<ConsentRecord> findLatestForRelationship(
            @Param("cas") ImmigrationCase cas,
            @Param("rel") CaseRelationship rel);
}
