package com.receipttracker.immigration.repository;

import com.receipttracker.immigration.model.Appointment;
import com.receipttracker.immigration.model.ImmigrationCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {
    List<Appointment> findByImmigrationCaseOrderByScheduledAtDesc(ImmigrationCase immigrationCase);
}
