package com.receipttracker.controller;

import com.receipttracker.dto.*;
import com.receipttracker.service.OrganizationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/organizations")
public class OrganizationController {

    @Autowired private OrganizationService orgService;

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateOrganizationRequest req) {
        try {
            return ResponseEntity.ok(orgService.create(req));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<List<OrganizationDTO>> listMine() {
        return ResponseEntity.ok(orgService.listMine());
    }

    @GetMapping("/{slug}")
    public ResponseEntity<?> get(@PathVariable String slug) {
        try {
            return ResponseEntity.ok(orgService.getBySlug(slug));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{slug}")
    public ResponseEntity<?> delete(@PathVariable String slug) {
        try {
            orgService.delete(slug);
            return ResponseEntity.ok(Map.of("message", "Organization deleted"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{slug}")
    public ResponseEntity<?> update(@PathVariable String slug,
                                    @RequestBody CreateOrganizationRequest req) {
        try {
            return ResponseEntity.ok(orgService.update(slug, req));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{slug}/members")
    public ResponseEntity<?> listMembers(@PathVariable String slug) {
        try {
            return ResponseEntity.ok(orgService.listMembers(slug));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{slug}/members")
    public ResponseEntity<?> invite(@PathVariable String slug,
                                    @RequestBody InviteMemberRequest req) {
        try {
            return ResponseEntity.ok(orgService.invite(slug, req));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{slug}/members/{id}")
    public ResponseEntity<?> revoke(@PathVariable String slug, @PathVariable Long id) {
        try {
            orgService.revokeMember(slug, id);
            return ResponseEntity.ok(Map.of("message", "Member revoked"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{slug}/public-store")
    public ResponseEntity<?> setPublicStore(@PathVariable String slug,
                                            @RequestBody Map<String, Boolean> body) {
        try {
            boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
            return ResponseEntity.ok(orgService.setPublicStore(slug, enabled));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
