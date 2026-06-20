package com.receipttracker.immigration.repository;

import com.receipttracker.immigration.model.ProfileDataRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProfileDataRequestRepository extends JpaRepository<ProfileDataRequest, Long> {
    Optional<ProfileDataRequest> findByToken(String token);
    List<ProfileDataRequest> findByCaseIdOrderByCreatedAtDesc(Long caseId);
}
