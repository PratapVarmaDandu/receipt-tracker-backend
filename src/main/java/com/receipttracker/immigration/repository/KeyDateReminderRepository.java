package com.receipttracker.immigration.repository;

import com.receipttracker.immigration.model.KeyDate;
import com.receipttracker.immigration.model.KeyDateReminder;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KeyDateReminderRepository extends JpaRepository<KeyDateReminder, Long> {
    boolean existsByKeyDateAndDaysBeforeDateAndRecipientEmail(KeyDate keyDate, int daysBeforeDate, String recipientEmail);
}
