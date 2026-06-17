package com.receipttracker.immigration.repository;

import com.receipttracker.immigration.model.OrgPartnership;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrgPartnershipRepository extends JpaRepository<OrgPartnership, Long> {

    List<OrgPartnership> findByEmployerOrgId(Long employerOrgId);

    List<OrgPartnership> findByLawFirmOrgId(Long lawFirmOrgId);
}
