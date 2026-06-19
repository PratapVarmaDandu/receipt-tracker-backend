package com.receipttracker.immigration.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "imm_message_thread_reads",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "thread_id"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessageThreadRead {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "thread_id", nullable = false)
    private Long threadId;

    @Column(name = "last_read_at", nullable = false)
    private LocalDateTime lastReadAt;
}
