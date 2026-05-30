package com.receipttracker.repository;

import com.receipttracker.model.ExpenseGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExpenseGroupRepository extends JpaRepository<ExpenseGroup, Long> {
    Optional<ExpenseGroup> findByInviteToken(String inviteToken);
}
