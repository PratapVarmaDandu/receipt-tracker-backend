package com.receipttracker.immigration.controller;

import com.receipttracker.immigration.dto.CreatePartnershipRequest;
import com.receipttracker.immigration.service.OrgPartnershipService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/immigration/partnerships")
public class OrgPartnershipController {

    private static final Logger log = LoggerFactory.getLogger(OrgPartnershipController.class);

    @Autowired private OrgPartnershipService partnershipService;

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreatePartnershipRequest req) {
        log.info("POST /api/immigration/partnerships employer={} lawFirm={}", req.employerOrgId(), req.lawFirmOrgId());
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(partnershipService.create(req));
        } catch (RuntimeException e) {
            return denied(e);
        }
    }

    @GetMapping("/mine")
    public ResponseEntity<?> listMine() {
        try {
            return ResponseEntity.ok(partnershipService.listMine());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}/accept")
    public ResponseEntity<?> accept(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(partnershipService.accept(id));
        } catch (RuntimeException e) {
            return denied(e);
        }
    }

    @PutMapping("/{id}/end")
    public ResponseEntity<?> end(@PathVariable Long id) {
        try {
            partnershipService.end(id);
            return ResponseEntity.noContent().build();
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
