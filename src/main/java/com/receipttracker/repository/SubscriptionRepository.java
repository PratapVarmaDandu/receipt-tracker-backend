package com.receipttracker.repository;

import com.receipttracker.model.Subscription;
import com.receipttracker.model.SubscriptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    Optional<Subscription> findBySquareSubscriptionId(String squareSubscriptionId);

    List<Subscription> findBySquareCustomerId(String squareCustomerId);

    List<Subscription> findBySquareCustomerIdAndPlanId(String squareCustomerId, String planId);

    List<Subscription> findByUserId(Long userId);

    List<Subscription> findByStatusAndGracePeriodEndsAtBefore(SubscriptionStatus status, LocalDateTime cutoff);

    List<Subscription> findByStatusIn(List<SubscriptionStatus> statuses);
}
