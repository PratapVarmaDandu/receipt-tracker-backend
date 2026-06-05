package com.receipttracker.repository;

import com.receipttracker.model.FuelRecord;
import com.receipttracker.model.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FuelRecordRepository extends JpaRepository<FuelRecord, Long> {

    List<FuelRecord> findByVehicleOrderByOdometerAsc(Vehicle vehicle);

    List<FuelRecord> findByVehicleOrderByFillDateDesc(Vehicle vehicle);

    Optional<FuelRecord> findFirstByVehicleOrderByOdometerDesc(Vehicle vehicle);
}
