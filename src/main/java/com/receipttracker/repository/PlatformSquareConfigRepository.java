package com.receipttracker.repository;

import com.receipttracker.model.PlatformSquareConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PlatformSquareConfigRepository extends JpaRepository<PlatformSquareConfig, Long> {

    Optional<PlatformSquareConfig> findFirstBy();
}
