package com.receipttracker.immigration.repository;

import com.receipttracker.immigration.model.H1bCapRegistration;
import com.receipttracker.immigration.model.ImmigrationCase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface H1bCapRegistrationRepository extends JpaRepository<H1bCapRegistration, Long> {

    Optional<H1bCapRegistration> findByImmigrationCase(ImmigrationCase immigrationCase);

    List<H1bCapRegistration> findByRegistrationYear(int year);

    @Query("""
        SELECT r FROM H1bCapRegistration r
        WHERE r.registrationYear = :year
          AND r.immigrationCase.lawFirmImmOrgId = :lawFirmOrgId
        """)
    List<H1bCapRegistration> findByYearAndLawFirm(
            @Param("year") int year,
            @Param("lawFirmOrgId") Long lawFirmOrgId);
}
