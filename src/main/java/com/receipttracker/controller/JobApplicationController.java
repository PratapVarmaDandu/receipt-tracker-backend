package com.receipttracker.controller;

import com.receipttracker.dto.CreateInterviewRoundRequest;
import com.receipttracker.dto.CreateJobApplicationRequest;
import com.receipttracker.dto.InterviewRoundDTO;
import com.receipttracker.dto.JobApplicationDTO;
import com.receipttracker.service.JobApplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/jobs")
public class JobApplicationController {

    private static final Logger log = LoggerFactory.getLogger(JobApplicationController.class);

    @Autowired private JobApplicationService service;

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateJobApplicationRequest req) {
        long start = System.currentTimeMillis();
        log.info(">>> POST /api/jobs company={}", req.getCompanyName());
        try {
            JobApplicationDTO dto = service.create(req);
            log.info("<<< POST /api/jobs id={} {}ms", dto.getId(), System.currentTimeMillis() - start);
            return ResponseEntity.ok(dto);
        } catch (Exception e) {
            log.error("!!! POST /api/jobs: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) String status) {
        long start = System.currentTimeMillis();
        log.trace(">>> GET /api/jobs status={}", status);
        try {
            List<JobApplicationDTO> dtos = service.list(status);
            log.info("<<< GET /api/jobs count={} {}ms", dtos.size(), System.currentTimeMillis() - start);
            return ResponseEntity.ok(dtos);
        } catch (Exception e) {
            log.error("!!! GET /api/jobs: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/summary")
    public ResponseEntity<?> summary() {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> s = service.getSummary();
            log.info("<<< GET /api/jobs/summary {}ms", System.currentTimeMillis() - start);
            return ResponseEntity.ok(s);
        } catch (Exception e) {
            log.error("!!! GET /api/jobs/summary: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(service.getById(id));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody CreateJobApplicationRequest req) {
        long start = System.currentTimeMillis();
        log.info(">>> PUT /api/jobs/{}", id);
        try {
            JobApplicationDTO dto = service.update(id, req);
            log.info("<<< PUT /api/jobs/{} {}ms", id, System.currentTimeMillis() - start);
            return ResponseEntity.ok(dto);
        } catch (Exception e) {
            log.error("!!! PUT /api/jobs/{}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        log.info(">>> DELETE /api/jobs/{}", id);
        try {
            service.delete(id);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            log.error("!!! DELETE /api/jobs/{}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/interviews")
    public ResponseEntity<?> addInterview(@PathVariable Long id, @RequestBody CreateInterviewRoundRequest req) {
        try {
            InterviewRoundDTO dto = service.addInterviewRound(id, req);
            return ResponseEntity.ok(dto);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}/interviews/{rid}")
    public ResponseEntity<?> updateInterview(@PathVariable Long id, @PathVariable Long rid,
                                              @RequestBody CreateInterviewRoundRequest req) {
        try {
            return ResponseEntity.ok(service.updateInterviewRound(id, rid, req));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}/interviews/{rid}")
    public ResponseEntity<?> deleteInterview(@PathVariable Long id, @PathVariable Long rid) {
        try {
            service.deleteInterviewRound(id, rid);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
