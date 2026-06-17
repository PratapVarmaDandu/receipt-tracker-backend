package com.receipttracker.immigration.repository;

import com.receipttracker.immigration.model.FormInstance;
import com.receipttracker.immigration.model.FormType;
import com.receipttracker.immigration.model.ImmigrationCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FormInstanceRepository extends JpaRepository<FormInstance, Long> {
    List<FormInstance> findByImmigrationCaseOrderByFormType(ImmigrationCase immigrationCase);
    Optional<FormInstance> findByImmigrationCaseAndFormType(ImmigrationCase immigrationCase, FormType formType);
    boolean existsByImmigrationCaseAndFormType(ImmigrationCase immigrationCase, FormType formType);
}
