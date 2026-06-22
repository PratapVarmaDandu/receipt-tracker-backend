package com.receipttracker.immigration.controller;

import com.receipttracker.config.ApiErrors;
import com.receipttracker.immigration.service.FormShareService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/immigration/forms/shared")
public class FormShareController {

    private static final Logger log = LoggerFactory.getLogger(FormShareController.class);

    @Autowired private FormShareService formShareService;

    /** Public — no auth required. Returns form data for the share token. */
    @GetMapping("/{token}")
    public ResponseEntity<?> getByToken(@PathVariable String token) {
        log.info("GET /api/immigration/forms/shared/{}", token);
        try {
            return ResponseEntity.ok(formShareService.getByToken(token));
        } catch (RuntimeException e) {
            String msg = ApiErrors.safeMessage(e);
            if (msg != null && (msg.contains("expired") || msg.contains("Invalid"))) {
                return ResponseEntity.status(HttpStatus.GONE).body(Map.of("error", msg));
            }
            log.error("!!! FormShareController getByToken failed: {}", msg);
            return ResponseEntity.badRequest().body(Map.of("error", msg));
        }
    }
}
