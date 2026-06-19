package com.receipttracker.immigration.repository;

import com.receipttracker.immigration.model.FormShare;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FormShareRepository extends JpaRepository<FormShare, Long> {
    Optional<FormShare> findByToken(String token);
    List<FormShare> findByFormInstanceId(Long formInstanceId);
}
