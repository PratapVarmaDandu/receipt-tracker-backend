package com.receipttracker.immigration.controller;

import com.receipttracker.immigration.service.MessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/immigration/cases/{caseId}/messages")
public class MessageController {

    private static final Logger log = LoggerFactory.getLogger(MessageController.class);

    @Autowired private MessageService messageService;

    @GetMapping("/{channel}")
    public ResponseEntity<?> getMessages(@PathVariable Long caseId, @PathVariable String channel) {
        log.info("GET /api/immigration/cases/{}/messages/{}", caseId, channel);
        try { return ResponseEntity.ok(messageService.getMessages(caseId, channel)); }
        catch (RuntimeException e) { return handleError(e); }
    }

    @PostMapping("/{channel}")
    public ResponseEntity<?> sendMessage(
            @PathVariable Long caseId,
            @PathVariable String channel,
            @RequestBody SendMessageRequest req) {
        log.info("POST /api/immigration/cases/{}/messages/{}", caseId, channel);
        try { return ResponseEntity.status(HttpStatus.CREATED)
                .body(messageService.sendMessage(caseId, channel, req.content())); }
        catch (RuntimeException e) { return handleError(e); }
    }

    private ResponseEntity<?> handleError(RuntimeException e) {
        String msg = e.getMessage();
        if (msg != null && msg.startsWith("Access denied"))
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", msg));
        log.error("!!! MessageController error: {}", msg);
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    record SendMessageRequest(String content) {}
}
