package com.receipttracker.controller;

import com.receipttracker.dto.RegisterDeviceTokenRequest;
import com.receipttracker.dto.UnregisterDeviceTokenRequest;
import com.receipttracker.service.PushNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/push")
public class PushController {

    private static final Logger log = LoggerFactory.getLogger(PushController.class);

    @Autowired private PushNotificationService pushService;

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterDeviceTokenRequest req) {
        log.info(">>> POST /api/push/register platform={}", req.getPlatform());
        try {
            pushService.registerToken(req.getToken(), req.getPlatform());
            log.info("<<< POST /api/push/register success");
            return ResponseEntity.ok(Map.of("registered", true));
        } catch (Exception e) {
            log.warn("!!! POST /api/push/register: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/register")
    public ResponseEntity<?> unregister(@RequestBody UnregisterDeviceTokenRequest req) {
        log.info(">>> DELETE /api/push/register");
        pushService.unregisterToken(req.getToken());
        log.info("<<< DELETE /api/push/register success");
        return ResponseEntity.ok(Map.of("unregistered", true));
    }
}
