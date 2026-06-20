package com.receipttracker.immigration.controller;

import com.receipttracker.immigration.dto.CreateI9RecordRequest;
import com.receipttracker.immigration.service.I9RecordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/immigration/orgs/{orgId}/i9")
public class I9Controller {

    private static final Logger log = LoggerFactory.getLogger(I9Controller.class);

    @Autowired private I9RecordService i9Service;

    @PostMapping
    public ResponseEntity<?> create(@PathVariable Long orgId, @RequestBody CreateI9RecordRequest req) {
        log.info("POST /api/immigration/orgs/{}/i9", orgId);
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(i9Service.create(orgId, req));
        } catch (RuntimeException e) {
            return denied(e);
        }
    }

    @GetMapping
    public ResponseEntity<?> list(@PathVariable Long orgId) {
        try {
            return ResponseEntity.ok(i9Service.list(orgId));
        } catch (RuntimeException e) {
            return denied(e);
        }
    }

    @PutMapping("/{recordId}")
    public ResponseEntity<?> update(
            @PathVariable Long orgId,
            @PathVariable Long recordId,
            @RequestBody CreateI9RecordRequest req) {
        log.info("PUT /api/immigration/orgs/{}/i9/{}", orgId, recordId);
        try {
            return ResponseEntity.ok(i9Service.update(orgId, recordId, req));
        } catch (RuntimeException e) {
            return denied(e);
        }
    }

    @GetMapping("/expiring")
    public ResponseEntity<?> listExpiring(
            @PathVariable Long orgId,
            @RequestParam(defaultValue = "90") int days) {
        try {
            return ResponseEntity.ok(i9Service.listExpiring(orgId, days));
        } catch (RuntimeException e) {
            return denied(e);
        }
    }

    private ResponseEntity<?> denied(RuntimeException e) {
        String msg = e.getMessage();
        if (msg != null && msg.startsWith("Access denied")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", msg));
        }
        log.error("!!! {}", msg);
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }
}
