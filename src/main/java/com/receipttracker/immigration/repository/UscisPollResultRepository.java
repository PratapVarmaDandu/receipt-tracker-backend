package com.receipttracker.immigration.repository;

import com.receipttracker.immigration.model.UscisPollResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UscisPollResultRepository extends JpaRepository<UscisPollResult, Long> {
    List<UscisPollResult> findByCaseIdOrderByPolledAtDesc(Long caseId);
    Optional<UscisPollResult> findFirstByCaseIdOrderByPolledAtDesc(Long caseId);
}
