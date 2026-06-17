package com.receipttracker.immigration.controller;

import com.receipttracker.immigration.dto.CreateImmOrgRequest;
import com.receipttracker.immigration.dto.InviteMemberRequest;
import com.receipttracker.immigration.service.ImmOrgService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/immigration/orgs")
public class ImmOrgController {

    private static final Logger log = LoggerFactory.getLogger(ImmOrgController.class);

    @Autowired private ImmOrgService immOrgService;

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateImmOrgRequest req) {
        log.info("POST /api/immigration/orgs name={}", req.name());
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(immOrgService.create(req));
        } catch (Exception e) {
            log.error("!!! create org failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/mine")
    public ResponseEntity<?> listMine() {
        try {
            return ResponseEntity.ok(immOrgService.listMine());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(immOrgService.getById(id));
        } catch (RuntimeException e) {
            return denied(e);
        }
    }

    @PostMapping("/{id}/members")
    public ResponseEntity<?> inviteMember(@PathVariable Long id, @RequestBody InviteMemberRequest req) {
        log.info("POST /api/immigration/orgs/{}/members email={}", id, req.email());
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(immOrgService.inviteMember(id, req));
        } catch (RuntimeException e) {
            return denied(e);
        }
    }

    @GetMapping("/{id}/members")
    public ResponseEntity<?> listMembers(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(immOrgService.listMembers(id));
        } catch (RuntimeException e) {
            return denied(e);
        }
    }

    @DeleteMapping("/{id}/members/{mid}")
    public ResponseEntity<?> removeMember(@PathVariable Long id, @PathVariable Long mid) {
        try {
            immOrgService.removeMember(id, mid);
            return ResponseEntity.noContent().build();
        } catch (RuntimeException e) {
            return denied(e);
        }
    }

    // public — no auth enforced in controller; service returns public info only
    @GetMapping("/join/{token}")
    public ResponseEntity<?> getJoinInfo(@PathVariable String token) {
        try {
            return ResponseEntity.ok(immOrgService.getJoinInfo(token));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/join/{token}")
    public ResponseEntity<?> acceptInvite(@PathVariable String token) {
        try {
            return ResponseEntity.ok(immOrgService.acceptInvite(token));
        } catch (RuntimeException e) {
            return denied(e);
        }
    }

    private ResponseEntity<?> denied(RuntimeException e) {
        String msg = e.getMessage();
        if (msg != null && (msg.startsWith("Access denied") || msg.startsWith("This invite"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", msg));
        }
        log.error("!!! {}", msg);
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }
}
