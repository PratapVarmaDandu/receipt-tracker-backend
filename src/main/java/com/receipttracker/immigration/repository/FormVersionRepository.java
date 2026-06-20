package com.receipttracker.immigration.repository;

import com.receipttracker.immigration.model.FormVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FormVersionRepository extends JpaRepository<FormVersion, Long> {
    List<FormVersion> findByFormTypeOrderByCreatedAtDesc(String formType);
    Optional<FormVersion> findFirstByFormTypeAndStatusOrderByCreatedAtDesc(String formType, String status);
    List<FormVersion> findAllByOrderByCreatedAtDesc();
}
