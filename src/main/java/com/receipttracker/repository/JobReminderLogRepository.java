package com.receipttracker.repository;

import com.receipttracker.model.JobReminderLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;

public interface JobReminderLogRepository extends JpaRepository<JobReminderLog, Long> {

    boolean existsByJobApplicationIdAndRemindedDate(Long jobApplicationId, LocalDate remindedDate);
}
