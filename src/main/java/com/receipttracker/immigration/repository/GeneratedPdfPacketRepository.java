package com.receipttracker.immigration.repository;

import com.receipttracker.immigration.model.GeneratedPdfPacket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GeneratedPdfPacketRepository extends JpaRepository<GeneratedPdfPacket, Long> {
    List<GeneratedPdfPacket> findByPackageIdOrderByCreatedAtDesc(Long packageId);
    Optional<GeneratedPdfPacket> findByIdAndPackageId(Long id, Long packageId);
}
