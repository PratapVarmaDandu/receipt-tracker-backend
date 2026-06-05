package com.receipttracker.repository;

import com.receipttracker.model.ExpenseShare;
import com.receipttracker.model.ExpenseShareItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExpenseShareItemRepository extends JpaRepository<ExpenseShareItem, Long> {
    List<ExpenseShareItem> findByShare(ExpenseShare share);
}
