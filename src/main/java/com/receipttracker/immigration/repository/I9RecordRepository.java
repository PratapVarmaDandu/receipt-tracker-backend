package com.receipttracker.immigration.repository;

import com.receipttracker.immigration.model.I9Record;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface I9RecordRepository extends JpaRepository<I9Record, Long> {

    List<I9Record> findByEmployerImmOrgIdOrderByCreatedAtDesc(Long employerImmOrgId);

    List<I9Record> findByEmployerImmOrgIdAndExpiryDateBetween(Long orgId, LocalDate from, LocalDate to);
}
