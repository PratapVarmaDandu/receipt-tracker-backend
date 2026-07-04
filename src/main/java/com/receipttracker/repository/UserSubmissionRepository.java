package com.receipttracker.repository;

import com.receipttracker.model.SubmissionType;
import com.receipttracker.model.UserSubmission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserSubmissionRepository extends JpaRepository<UserSubmission, Long> {

    List<UserSubmission> findAllByOrderByCreatedAtDesc();

    List<UserSubmission> findByTypeOrderByCreatedAtDesc(SubmissionType type);
}
