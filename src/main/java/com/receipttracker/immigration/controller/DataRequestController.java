package com.receipttracker.immigration.controller;

import com.receipttracker.immigration.dto.*;
import com.receipttracker.immigration.service.ProfileDataRequestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/immigration")
public class DataRequestController {

    private static final Logger log = LoggerFactory.getLogger(DataRequestController.class);

    @Autowired private ProfileDataRequestService service;

    /** POST /api/immigration/cases/{id}/data-requests — create request (ATTORNEY/PARALEGAL) */
    @PostMapping("/cases/{id}/data-requests")
    public ResponseEntity<?> create(@PathVariable Long id,
                                    @RequestBody CreateDataRequestRequest req) {
        log.info("POST /api/immigration/cases/{}/data-requests", id);
        try {
            return ResponseEntity.status(201).body(service.create(id, req));
        } catch (Exception e) {
            return denied(e.getMessage());
        }
    }

    /** GET /api/immigration/cases/{id}/data-requests — list (ATTORNEY) */
    @GetMapping("/cases/{id}/data-requests")
    public ResponseEntity<?> list(@PathVariable Long id) {
        log.info("GET /api/immigration/cases/{}/data-requests", id);
        try {
            List<ProfileDataRequestDTO> result = service.listForCase(id);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return denied(e.getMessage());
        }
    }

    /** GET /api/immigration/data-requests/{token} — public; prefilled data for intake page */
    @GetMapping("/data-requests/{token}")
    public ResponseEntity<?> getPublic(@PathVariable String token) {
        log.info("GET /api/immigration/data-requests/***");
        try {
            return ResponseEntity.ok(service.getPublic(token));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** POST /api/immigration/data-requests/{token}/submit — auth required; email match validated */
    @PostMapping("/data-requests/{token}/submit")
    public ResponseEntity<?> submit(@PathVariable String token,
                                    @RequestBody DataRequestSubmitRequest req) {
        log.info("POST /api/immigration/data-requests/***/submit");
        try {
            return ResponseEntity.ok(service.submit(token, req));
        } catch (Exception e) {
            return denied(e.getMessage());
        }
    }

    private ResponseEntity<Map<String, String>> denied(String msg) {
        if (msg != null && msg.startsWith("Access denied")) {
            return ResponseEntity.status(403).body(Map.of("error", msg));
        }
        return ResponseEntity.badRequest().body(Map.of("error", msg != null ? msg : "Bad request"));
    }
}
