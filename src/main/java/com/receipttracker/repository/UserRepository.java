package com.receipttracker.repository;

import com.receipttracker.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByGoogleId(String googleId);
    Optional<User> findByAppleId(String appleId);
    Optional<User> findByEmail(String email);
    Optional<User> findByReferralCode(String referralCode);
}
