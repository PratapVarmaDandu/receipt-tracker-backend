package com.receipttracker.immigration.repository;

import com.receipttracker.immigration.model.ImmigrationCase;
import com.receipttracker.immigration.model.MessageChannel;
import com.receipttracker.immigration.model.MessageThread;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MessageThreadRepository extends JpaRepository<MessageThread, Long> {
    Optional<MessageThread> findByImmigrationCaseAndChannel(ImmigrationCase c, MessageChannel channel);
}
