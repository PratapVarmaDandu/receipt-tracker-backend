package com.receipttracker.immigration.repository;

import com.receipttracker.immigration.model.CaseTask;
import com.receipttracker.immigration.model.ImmigrationCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CaseTaskRepository extends JpaRepository<CaseTask, Long> {

    List<CaseTask> findByImmigrationCaseOrderByDueDateAscCreatedAtAsc(ImmigrationCase immigrationCase);

    List<CaseTask> findByImmigrationCaseAndCompletedAtIsNull(ImmigrationCase immigrationCase);

    List<CaseTask> findByAssignedToMemberId(Long memberId);
}
