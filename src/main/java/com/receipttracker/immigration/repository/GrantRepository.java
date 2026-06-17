package com.receipttracker.immigration.repository;

import com.receipttracker.immigration.model.Grant;
import com.receipttracker.immigration.model.GrantScope;
import com.receipttracker.immigration.model.ImmigrationCase;
import com.receipttracker.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface GrantRepository extends JpaRepository<Grant, Long> {

    List<Grant> findByImmigrationCaseAndRevokedAtIsNull(ImmigrationCase immigrationCase);

    @Query("""
        SELECT COUNT(g) > 0 FROM Grant g
        WHERE g.immigrationCase.id = :caseId
          AND g.scope = :scope
          AND g.revokedAt IS NULL
          AND (g.subjectUser.id = :userId OR g.subjectImmOrgId IN :orgIds)
        """)
    boolean existsActiveGrant(
            @Param("caseId") Long caseId,
            @Param("scope") GrantScope scope,
            @Param("userId") Long userId,
            @Param("orgIds") List<Long> orgIds);
}
