package com.receipttracker.immigration.service;

import com.receipttracker.immigration.model.FeedVisibility;
import com.receipttracker.immigration.model.ImmAuditEvent;
import com.receipttracker.immigration.model.ImmigrationCase;
import com.receipttracker.immigration.repository.ImmAuditEventRepository;
import com.receipttracker.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Append-only audit service.
 * append() is the ONLY write method.
 * No update or delete methods exist — the audit log is permanent.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    @Autowired private ImmAuditEventRepository auditRepo;

    @Transactional
    public void append(ImmigrationCase c, User actor, String action, String detail, FeedVisibility visibility) {
        try {
            ImmAuditEvent event = new ImmAuditEvent();
            event.setImmigrationCase(c);
            event.setActor(actor);
            event.setAction(action);
            event.setDetail(detail);
            event.setVisibility(visibility);
            auditRepo.save(event);
            log.debug("Audit: case={} action={} actor={}", c.getId(), action, actor != null ? actor.getId() : "SYSTEM");
        } catch (Exception e) {
            log.error("!!! Failed to append audit event action={}: {}", action, e.getMessage());
        }
    }

    @Transactional
    public void appendSystem(ImmigrationCase c, String action, String detail) {
        append(c, null, action, detail, FeedVisibility.ALL);
    }
}
