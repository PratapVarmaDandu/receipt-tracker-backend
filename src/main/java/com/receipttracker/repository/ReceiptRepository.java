package com.receipttracker.repository;

import com.receipttracker.model.Receipt;
import com.receipttracker.model.StoreType;
import com.receipttracker.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ReceiptRepository extends JpaRepository<Receipt, Long> {

    // Returns the user's own receipts plus shared demo rows (user IS NULL)
    @Query("SELECT r FROM Receipt r WHERE r.user = :user OR r.user IS NULL ORDER BY r.purchaseDateTime DESC")
    List<Receipt> findByUserOrDemo(@Param("user") User user);

    @Query("SELECT r FROM Receipt r WHERE (r.user = :user OR r.user IS NULL) AND r.purchaseDateTime BETWEEN :start AND :end ORDER BY r.purchaseDateTime DESC")
    List<Receipt> findByUserOrDemoAndDateBetween(@Param("user") User user,
                                                  @Param("start") LocalDateTime start,
                                                  @Param("end") LocalDateTime end);

    /** Only the user's own receipts — excludes shared demo rows. */
    List<Receipt> findByUser(User user);

    List<Receipt> findAllByOrderByPurchaseDateTimeDesc();

    List<Receipt> findByStoreType(StoreType storeType);

    List<Receipt> findByCardBank(String cardBank);

    @Query("SELECT DISTINCT r.cardBank FROM Receipt r WHERE r.cardBank IS NOT NULL")
    List<String> findDistinctCardBanks();
}
