package com.receipttracker.repository;

import com.receipttracker.model.AppFeature;
import com.receipttracker.model.User;
import com.receipttracker.model.UserFeatureGrant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserFeatureRepository extends JpaRepository<UserFeatureGrant, Long> {

    List<UserFeatureGrant> findByUser(User user);

    Optional<UserFeatureGrant> findByUserAndFeature(User user, AppFeature feature);

    void deleteByUserAndFeature(User user, AppFeature feature);

    boolean existsByUserAndFeature(User user, AppFeature feature);
}
