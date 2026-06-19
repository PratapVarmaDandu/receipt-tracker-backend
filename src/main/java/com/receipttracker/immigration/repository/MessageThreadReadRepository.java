package com.receipttracker.immigration.repository;

import com.receipttracker.immigration.model.MessageThreadRead;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MessageThreadReadRepository extends JpaRepository<MessageThreadRead, Long> {
    Optional<MessageThreadRead> findByUserIdAndThreadId(Long userId, Long threadId);
}
