package com.receipttracker.repository;

import com.receipttracker.model.Referral;
import com.receipttracker.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;

public interface ReferralRepository extends JpaRepository<Referral, Long> {

    boolean existsByReferredUser(User referredUser);

    long countByReferrer(User referrer);

    long countByReferrerAndRewardGrantedTrueAndCreatedAtAfter(User referrer, LocalDateTime after);
}
