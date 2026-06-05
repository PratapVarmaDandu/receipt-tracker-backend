package com.receipttracker.repository;

import com.receipttracker.model.DocumentShare;
import com.receipttracker.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocumentShareRepository extends JpaRepository<DocumentShare, Long> {

    Optional<DocumentShare> findByShareToken(String shareToken);

    List<DocumentShare> findByOwnerOrderBySharedAtDesc(User owner);
}
