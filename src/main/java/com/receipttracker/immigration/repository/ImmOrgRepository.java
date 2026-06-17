package com.receipttracker.immigration.repository;

import com.receipttracker.immigration.model.ImmOrg;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ImmOrgRepository extends JpaRepository<ImmOrg, Long> {

    List<ImmOrg> findByOwnerUserId(Long ownerUserId);

    @Query("""
        SELECT o FROM ImmOrg o
        JOIN ImmOrgMember m ON m.immOrgId = o.id
        WHERE m.userId = :userId AND m.status = 'ACTIVE'
        """)
    List<ImmOrg> findByMemberUserId(@Param("userId") Long userId);
}
