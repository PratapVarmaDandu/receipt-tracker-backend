package com.receipttracker.immigration.repository;

import com.receipttracker.immigration.model.ImmigrationCase;
import com.receipttracker.immigration.model.KeyDate;
import com.receipttracker.immigration.model.KeyDateType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KeyDateRepository extends JpaRepository<KeyDate, Long> {
    List<KeyDate> findByImmigrationCaseOrderByDateAsc(ImmigrationCase immigrationCase);
    Optional<KeyDate> findByImmigrationCaseAndDateType(ImmigrationCase immigrationCase, KeyDateType type);
}
