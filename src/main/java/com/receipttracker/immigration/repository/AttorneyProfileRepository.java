package com.receipttracker.immigration.repository;

import com.receipttracker.immigration.model.AttorneyProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AttorneyProfileRepository extends JpaRepository<AttorneyProfile, Long> {

    Optional<AttorneyProfile> findByImmOrgMemberId(Long immOrgMemberId);
}
