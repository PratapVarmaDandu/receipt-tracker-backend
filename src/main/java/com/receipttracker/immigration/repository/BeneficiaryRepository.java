package com.receipttracker.immigration.repository;

import com.receipttracker.immigration.model.Beneficiary;
import com.receipttracker.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BeneficiaryRepository extends JpaRepository<Beneficiary, Long> {
    Optional<Beneficiary> findByUser(User user);
    boolean existsByUser(User user);
}
