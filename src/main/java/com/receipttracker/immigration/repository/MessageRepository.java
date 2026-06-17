package com.receipttracker.immigration.repository;

import com.receipttracker.immigration.model.Message;
import com.receipttracker.immigration.model.MessageThread;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {
    List<Message> findByThreadOrderByCreatedAtAsc(MessageThread thread);
}
