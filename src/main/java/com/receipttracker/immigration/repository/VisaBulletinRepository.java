package com.receipttracker.immigration.repository;

import com.receipttracker.immigration.model.VisaBulletinEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface VisaBulletinRepository extends JpaRepository<VisaBulletinEntry, Long> {

    List<VisaBulletinEntry> findByBulletinYearAndBulletinMonth(Integer year, Integer month);

    @Query("""
        SELECT v FROM VisaBulletinEntry v
        WHERE v.bulletinYear = (SELECT MAX(v2.bulletinYear) FROM VisaBulletinEntry v2)
          AND v.bulletinMonth = (
              SELECT MAX(v3.bulletinMonth) FROM VisaBulletinEntry v3
              WHERE v3.bulletinYear = (SELECT MAX(v4.bulletinYear) FROM VisaBulletinEntry v4)
          )
        ORDER BY v.preferenceCategory, v.countryOfChargeability
        """)
    List<VisaBulletinEntry> findLatestBulletin();

    Optional<VisaBulletinEntry> findByBulletinYearAndBulletinMonthAndPreferenceCategoryAndCountryOfChargeability(
            Integer year, Integer month, String category, String country);
}
