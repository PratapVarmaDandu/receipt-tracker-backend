package com.receipttracker.immigration.controller;

import com.receipttracker.config.ApiErrors;
import com.receipttracker.immigration.dto.ChecklistItemDTO;
import com.receipttracker.immigration.dto.GenerateChecklistRequest;
import com.receipttracker.immigration.dto.UpdateChecklistItemRequest;
import com.receipttracker.immigration.service.ChecklistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/immigration/cases/{caseId}/checklist")
public class ChecklistController {

    private static final Logger log = LoggerFactory.getLogger(ChecklistController.class);

    @Autowired private ChecklistService service;

    /** POST /api/immigration/cases/{caseId}/checklist/generate — ATTORNEY + PARALEGAL */
    @PostMapping("/generate")
    public ResponseEntity<?> generate(@PathVariable Long caseId,
                                      @RequestBody GenerateChecklistRequest req) {
        log.info("POST /api/immigration/cases/{}/checklist/generate", caseId);
        try {
            List<ChecklistItemDTO> items = service.generate(caseId, req);
            return ResponseEntity.ok(items);
        } catch (Exception e) {
            return denied(ApiErrors.safeMessage(e));
        }
    }

    /** GET /api/immigration/cases/{caseId}/checklist — READ_CASE */
    @GetMapping
    public ResponseEntity<?> list(@PathVariable Long caseId) {
        log.info("GET /api/immigration/cases/{}/checklist", caseId);
        try {
            List<ChecklistItemDTO> items = service.list(caseId);
            return ResponseEntity.ok(items);
        } catch (Exception e) {
            return denied(ApiErrors.safeMessage(e));
        }
    }

    /** PUT /api/immigration/cases/{caseId}/checklist/{itemId} — WRITE_CASE (VERIFIED/WAIVED: MANAGE_CHECKLISTS) */
    @PutMapping("/{itemId}")
    public ResponseEntity<?> update(@PathVariable Long caseId,
                                    @PathVariable Long itemId,
                                    @RequestBody UpdateChecklistItemRequest req) {
        log.info("PUT /api/immigration/cases/{}/checklist/{}", caseId, itemId);
        try {
            ChecklistItemDTO item = service.update(caseId, itemId, req);
            return ResponseEntity.ok(item);
        } catch (Exception e) {
            return denied(ApiErrors.safeMessage(e));
        }
    }

    private ResponseEntity<Map<String, String>> denied(String msg) {
        if (msg != null && msg.startsWith("Access denied")) {
            return ResponseEntity.status(403).body(Map.of("error", msg));
        }
        return ResponseEntity.badRequest().body(Map.of("error", msg != null ? msg : "Bad request"));
    }
}
