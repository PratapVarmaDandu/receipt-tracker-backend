package com.receipttracker.repository;

import com.receipttracker.model.JobApplication;
import com.receipttracker.model.JobApplicationStatus;
import com.receipttracker.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

public interface JobApplicationRepository extends JpaRepository<JobApplication, Long> {

    List<JobApplication> findByUserOrderByAppliedDateDesc(User user);

    List<JobApplication> findByUserAndStatusOrderByAppliedDateDesc(User user, JobApplicationStatus status);

    @Query("SELECT ja FROM JobApplication ja WHERE ja.user = :user " +
           "AND ja.followUpDate IS NOT NULL AND ja.followUpDate <= :today " +
           "AND ja.status NOT IN ('REJECTED', 'WITHDRAWN', 'GHOSTED', 'OFFER')")
    List<JobApplication> findFollowUpsDue(User user, LocalDate today);

    long countByUser(User user);

    long countByUserAndAppliedDateBetween(User user, LocalDate from, LocalDate to);
}
