package com.receipttracker.repository;

import com.receipttracker.model.MaintenanceRecord;
import com.receipttracker.model.MaintenanceType;
import com.receipttracker.model.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface MaintenanceRecordRepository extends JpaRepository<MaintenanceRecord, Long> {

    List<MaintenanceRecord> findByVehicleOrderByServiceDateDescMileageDesc(Vehicle vehicle);

    List<MaintenanceRecord> findByVehicleAndMaintenanceTypeOrderByServiceDateDesc(
            Vehicle vehicle, MaintenanceType type);

    /** Most recent service of a given type — for schedule "last performed" calculation. */
    Optional<MaintenanceRecord> findFirstByVehicleAndMaintenanceTypeOrderByServiceDateDescMileageDesc(
            Vehicle vehicle, MaintenanceType type);

    @Query("SELECT COALESCE(SUM(r.cost), 0) FROM MaintenanceRecord r WHERE r.vehicle = :vehicle AND r.cost IS NOT NULL")
    BigDecimal sumCostByVehicle(Vehicle vehicle);
}
