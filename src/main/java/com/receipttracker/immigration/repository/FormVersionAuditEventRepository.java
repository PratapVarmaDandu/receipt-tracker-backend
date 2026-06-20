package com.receipttracker.immigration.repository;

import com.receipttracker.immigration.model.FormVersionAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FormVersionAuditEventRepository extends JpaRepository<FormVersionAuditEvent, Long> {
    List<FormVersionAuditEvent> findByFormTypeOrderByCreatedAtDesc(String formType);
    List<FormVersionAuditEvent> findTop50ByOrderByCreatedAtDesc();
}
