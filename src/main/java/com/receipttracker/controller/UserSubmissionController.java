package com.receipttracker.controller;

import com.receipttracker.dto.UserSubmissionDTO;
import com.receipttracker.service.UserSubmissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/feedback")
public class UserSubmissionController {

    private static final Logger log = LoggerFactory.getLogger(UserSubmissionController.class);

    @Autowired private UserSubmissionService submissionService;

    @PostMapping
    public ResponseEntity<?> create(
            @RequestParam("type") String type,
            @RequestParam("message") String message,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "clientLog", required = false) String clientLog) {

        log.info("POST /api/feedback type={}", type);
        try {
            UserSubmissionDTO result = submissionService.create(type, message, file, clientLog);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("!!! feedback submission failed: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
