package com.receipttracker.immigration.controller;

import com.receipttracker.immigration.dto.CaseRfeDTO;
import com.receipttracker.immigration.dto.CreateRfeRequest;
import com.receipttracker.immigration.dto.UpdateRfeRequest;
import com.receipttracker.immigration.service.CaseRfeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/immigration/cases/{caseId}/rfe")
public class CaseRfeController {

    private static final Logger log = LoggerFactory.getLogger(CaseRfeController.class);

    @Autowired private CaseRfeService rfeService;

    @GetMapping
    public ResponseEntity<?> list(@PathVariable Long caseId) {
        log.info("GET /api/immigration/cases/{}/rfe", caseId);
        try {
            List<CaseRfeDTO> rfes = rfeService.list(caseId);
            return ResponseEntity.ok(rfes);
        } catch (RuntimeException e) {
            return denied(e);
        }
    }

    @PostMapping
    public ResponseEntity<?> create(@PathVariable Long caseId, @RequestBody CreateRfeRequest req) {
        log.info("POST /api/immigration/cases/{}/rfe category={}", caseId, req.uscisCategory());
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(rfeService.create(caseId, req));
        } catch (RuntimeException e) {
            return denied(e);
        }
    }

    @PutMapping("/{rfeId}")
    public ResponseEntity<?> update(@PathVariable Long caseId,
                                    @PathVariable Long rfeId,
                                    @RequestBody UpdateRfeRequest req) {
        log.info("PUT /api/immigration/cases/{}/rfe/{}", caseId, rfeId);
        try {
            return ResponseEntity.ok(rfeService.update(caseId, rfeId, req));
        } catch (RuntimeException e) {
            return denied(e);
        }
    }

    @PutMapping("/{rfeId}/respond")
    public ResponseEntity<?> respond(@PathVariable Long caseId, @PathVariable Long rfeId) {
        log.info("PUT /api/immigration/cases/{}/rfe/{}/respond", caseId, rfeId);
        try {
            return ResponseEntity.ok(rfeService.respond(caseId, rfeId));
        } catch (RuntimeException e) {
            return denied(e);
        }
    }

    private ResponseEntity<?> denied(RuntimeException e) {
        String msg = e.getMessage();
        if (msg != null && msg.startsWith("Access denied")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", msg));
        }
        log.error("!!! rfe error: {}", msg);
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }
}
