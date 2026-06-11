package com.receipttracker.repository;

import com.receipttracker.model.Organization;
import com.receipttracker.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrganizationRepository extends JpaRepository<Organization, Long> {
    Optional<Organization> findBySlug(String slug);
    List<Organization> findByOwner(User owner);
    boolean existsBySlug(String slug);
}
