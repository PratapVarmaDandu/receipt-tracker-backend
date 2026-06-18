package com.receipttracker.immigration.repository;

import com.receipttracker.immigration.model.Beneficiary;
import com.receipttracker.immigration.model.ImmigrationCase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ImmigrationCaseRepository extends JpaRepository<ImmigrationCase, Long> {

    List<ImmigrationCase> findByBeneficiaryOrderByCreatedAtDesc(Beneficiary beneficiary);

    // Cases visible to a user via any active Grant (user-level or org-level)
    @Query("""
        SELECT DISTINCT c FROM ImmigrationCase c
        JOIN Grant g ON g.immigrationCase = c
        WHERE g.revokedAt IS NULL
          AND (g.subjectUser.id = :userId OR g.subjectImmOrgId IN :orgIds)
        ORDER BY c.createdAt DESC
        """)
    List<ImmigrationCase> findAccessibleByUser(
            @Param("userId") Long userId,
            @Param("orgIds") List<Long> orgIds);

    List<ImmigrationCase> findByEmployerImmOrgIdOrderByCreatedAtDesc(Long employerImmOrgId);

    List<ImmigrationCase> findByLawFirmImmOrgIdOrderByCreatedAtDesc(Long lawFirmImmOrgId);

    Optional<ImmigrationCase> findByBeneficiaryInviteToken(String token);

    @Query("SELECT MAX(c.caseNumber) FROM ImmigrationCase c WHERE c.caseNumber LIKE :prefix%")
    Optional<String> findMaxCaseNumberWithPrefix(@Param("prefix") String prefix);
}
