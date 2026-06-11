package com.receipttracker.repository;

import com.receipttracker.model.OrgOrder;
import com.receipttracker.model.Organization;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrgOrderRepository extends JpaRepository<OrgOrder, Long> {
    List<OrgOrder> findByOrgOrderByPlacedAtDesc(Organization org);
    List<OrgOrder> findByOrgOrderByPlacedAtDesc(Organization org, Pageable pageable);
}
