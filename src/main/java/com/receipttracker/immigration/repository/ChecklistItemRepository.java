package com.receipttracker.immigration.repository;

import com.receipttracker.immigration.model.ChecklistItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChecklistItemRepository extends JpaRepository<ChecklistItem, Long> {
    List<ChecklistItem> findByCaseIdOrderByCategoryAscSortOrderAsc(Long caseId);
    Optional<ChecklistItem> findByCaseIdAndItemKey(Long caseId, String itemKey);
}
