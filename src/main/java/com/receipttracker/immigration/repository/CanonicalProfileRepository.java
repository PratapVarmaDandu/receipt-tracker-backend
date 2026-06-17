package com.receipttracker.immigration.repository;

import com.receipttracker.immigration.model.Beneficiary;
import com.receipttracker.immigration.model.CanonicalProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CanonicalProfileRepository extends JpaRepository<CanonicalProfile, Long> {
    Optional<CanonicalProfile> findByBeneficiary(Beneficiary beneficiary);
    boolean existsByBeneficiary(Beneficiary beneficiary);
}
