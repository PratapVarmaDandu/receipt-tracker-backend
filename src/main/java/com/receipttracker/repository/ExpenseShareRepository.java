package com.receipttracker.repository;

import com.receipttracker.model.ExpenseShare;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExpenseShareRepository extends JpaRepository<ExpenseShare, Long> {

    Optional<ExpenseShare> findByInviteToken(String token);

    List<ExpenseShare> findByReceiptId(Long receiptId);

    List<ExpenseShare> findByInviteeEmail(String email);

    List<ExpenseShare> findByInviterGoogleId(String googleId);
}
