package com.receipttracker.repository;

import com.receipttracker.model.PushDeviceToken;
import com.receipttracker.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PushDeviceTokenRepository extends JpaRepository<PushDeviceToken, Long> {

    List<PushDeviceToken> findByUser(User user);

    Optional<PushDeviceToken> findByUserAndToken(User user, String token);

    void deleteByToken(String token);
}
