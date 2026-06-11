package com.receipttracker.controller;

import com.receipttracker.service.OrganizationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/org")
public class OrgJoinController {

    @Autowired private OrganizationService orgService;

    /** Public — returns invite info without auth so the join page can show org/role details. */
    @GetMapping("/join/{token}")
    public ResponseEntity<?> getInvite(@PathVariable String token) {
        try {
            return ResponseEntity.ok(orgService.getInviteByToken(token));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Auth required — invitee accepts the invite. */
    @PostMapping("/join/{token}")
    public ResponseEntity<?> acceptInvite(@PathVariable String token) {
        try {
            return ResponseEntity.ok(orgService.acceptInvite(token));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
