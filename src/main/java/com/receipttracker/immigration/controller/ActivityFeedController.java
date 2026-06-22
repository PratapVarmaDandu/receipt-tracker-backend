package com.receipttracker.immigration.controller;

import com.receipttracker.config.ApiErrors;
import com.receipttracker.immigration.service.ActivityFeedService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/immigration/cases/{caseId}/feed")
public class ActivityFeedController {

    private static final Logger log = LoggerFactory.getLogger(ActivityFeedController.class);

    @Autowired private ActivityFeedService feedService;

    @GetMapping
    public ResponseEntity<?> getFeed(@PathVariable Long caseId) {
        log.info("GET /api/immigration/cases/{}/feed", caseId);
        try { return ResponseEntity.ok(feedService.getFeed(caseId)); }
        catch (RuntimeException e) { return handleError(e); }
    }

    private ResponseEntity<?> handleError(RuntimeException e) {
        String msg = ApiErrors.safeMessage(e);
        if (msg != null && msg.startsWith("Access denied"))
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", msg));
        log.error("!!! ActivityFeedController error: {}", msg);
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }
}
