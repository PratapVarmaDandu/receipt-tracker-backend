package com.receipttracker.repository;

import com.receipttracker.model.User;
import com.receipttracker.model.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VehicleRepository extends JpaRepository<Vehicle, Long> {
    List<Vehicle> findByUserOrderByModelYearDescMakeAsc(User user);
    long countByUser(User user);
}
