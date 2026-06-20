package com.receipttracker.immigration.controller;

import com.receipttracker.immigration.service.VisaBulletinService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/immigration")
public class VisaBulletinController {

    private static final Logger log = LoggerFactory.getLogger(VisaBulletinController.class);

    @Autowired private VisaBulletinService bulletinService;

    /** Public — no auth required; returns latest scraped bulletin entries. */
    @GetMapping("/visa-bulletin/latest")
    public ResponseEntity<?> getLatest() {
        try {
            return ResponseEntity.ok(bulletinService.getLatest());
        } catch (Exception e) {
            log.error("!!! getLatest: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** READ_CASE required; returns priority date status widget data. */
    @GetMapping("/cases/{id}/priority-date-status")
    public ResponseEntity<?> getPriorityDateStatus(@PathVariable Long id) {
        log.info("GET /api/immigration/cases/{}/priority-date-status", id);
        try {
            return ResponseEntity.ok(bulletinService.getPriorityDateStatus(id));
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            if (msg != null && msg.startsWith("Access denied")) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", msg));
            }
            log.error("!!! getPriorityDateStatus: {}", msg);
            return ResponseEntity.badRequest().body(Map.of("error", msg));
        }
    }
}
