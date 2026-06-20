package com.receipttracker.immigration.repository;

import com.receipttracker.immigration.model.ChecklistTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChecklistTemplateRepository extends JpaRepository<ChecklistTemplate, Long> {
    List<ChecklistTemplate> findByFormTypeInOrderBySortOrderAsc(List<String> formTypes);
    boolean existsByFormType(String formType);
}
