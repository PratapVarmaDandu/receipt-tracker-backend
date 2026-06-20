package com.receipttracker.immigration.repository;

import com.receipttracker.immigration.model.ImmigrationCase;
import com.receipttracker.immigration.model.KeyDate;
import com.receipttracker.immigration.model.KeyDateType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface KeyDateRepository extends JpaRepository<KeyDate, Long> {
    List<KeyDate> findByImmigrationCaseOrderByDateAsc(ImmigrationCase immigrationCase);
    Optional<KeyDate> findByImmigrationCaseAndDateType(ImmigrationCase immigrationCase, KeyDateType type);

    @Query("SELECT kd FROM KeyDate kd WHERE kd.date BETWEEN :from AND :to")
    List<KeyDate> findInWindow(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
