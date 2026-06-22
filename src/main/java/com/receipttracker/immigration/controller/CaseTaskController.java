package com.receipttracker.immigration.controller;

import com.receipttracker.config.ApiErrors;
import com.receipttracker.immigration.dto.CaseTaskDTO;
import com.receipttracker.immigration.dto.CreateCaseTaskRequest;
import com.receipttracker.immigration.dto.UpdateCaseTaskRequest;
import com.receipttracker.immigration.service.CaseTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/immigration/cases/{caseId}/tasks")
public class CaseTaskController {

    private static final Logger log = LoggerFactory.getLogger(CaseTaskController.class);

    @Autowired private CaseTaskService taskService;

    @GetMapping
    public ResponseEntity<?> list(@PathVariable Long caseId) {
        log.info("GET /api/immigration/cases/{}/tasks", caseId);
        try {
            List<CaseTaskDTO> tasks = taskService.list(caseId);
            return ResponseEntity.ok(tasks);
        } catch (RuntimeException e) {
            return denied(e);
        }
    }

    @PostMapping
    public ResponseEntity<?> create(@PathVariable Long caseId, @RequestBody CreateCaseTaskRequest req) {
        log.info("POST /api/immigration/cases/{}/tasks title={}", caseId, req.title());
        try {
            CaseTaskDTO dto = taskService.create(caseId, req);
            return ResponseEntity.status(HttpStatus.CREATED).body(dto);
        } catch (RuntimeException e) {
            return denied(e);
        }
    }

    @PutMapping("/{taskId}")
    public ResponseEntity<?> update(
            @PathVariable Long caseId,
            @PathVariable Long taskId,
            @RequestBody UpdateCaseTaskRequest req) {
        log.info("PUT /api/immigration/cases/{}/tasks/{}", caseId, taskId);
        try {
            return ResponseEntity.ok(taskService.update(caseId, taskId, req));
        } catch (RuntimeException e) {
            return denied(e);
        }
    }

    @PutMapping("/{taskId}/complete")
    public ResponseEntity<?> complete(@PathVariable Long caseId, @PathVariable Long taskId) {
        log.info("PUT /api/immigration/cases/{}/tasks/{}/complete", caseId, taskId);
        try {
            return ResponseEntity.ok(taskService.complete(caseId, taskId));
        } catch (RuntimeException e) {
            return denied(e);
        }
    }

    @DeleteMapping("/{taskId}")
    public ResponseEntity<?> delete(@PathVariable Long caseId, @PathVariable Long taskId) {
        log.info("DELETE /api/immigration/cases/{}/tasks/{}", caseId, taskId);
        try {
            taskService.delete(caseId, taskId);
            return ResponseEntity.noContent().build();
        } catch (RuntimeException e) {
            return denied(e);
        }
    }

    private ResponseEntity<?> denied(RuntimeException e) {
        String msg = ApiErrors.safeMessage(e);
        if (msg != null && msg.startsWith("Access denied")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", msg));
        }
        log.error("!!! task error: {}", msg);
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }
}
