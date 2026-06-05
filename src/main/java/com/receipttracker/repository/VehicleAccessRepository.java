package com.receipttracker.repository;

import com.receipttracker.model.User;
import com.receipttracker.model.Vehicle;
import com.receipttracker.model.VehicleAccess;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VehicleAccessRepository extends JpaRepository<VehicleAccess, Long> {

    List<VehicleAccess> findByVehicleOrderByGrantedAtDesc(Vehicle vehicle);

    List<VehicleAccess> findByUserAndStatus(User user, VehicleAccess.AccessStatus status);

    Optional<VehicleAccess> findByInviteToken(String token);

    boolean existsByVehicleAndInviteeEmailAndStatusNot(
            Vehicle vehicle, String email, VehicleAccess.AccessStatus status);

    boolean existsByVehicleAndUserAndStatus(
            Vehicle vehicle, User user, VehicleAccess.AccessStatus status);
}
