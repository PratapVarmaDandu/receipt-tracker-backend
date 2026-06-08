package com.receipttracker.controller;

import com.receipttracker.dto.CreateGroupRequest;
import com.receipttracker.dto.GroupDTO;
import com.receipttracker.service.GroupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/groups")
public class GroupController {

    private static final Logger log = LoggerFactory.getLogger(GroupController.class);

    @Autowired
    private GroupService groupService;

    @PostMapping
    public ResponseEntity<?> createGroup(@RequestBody CreateGroupRequest req) {
        log.trace(">>> POST /api/groups name={}", req.getName());
        try {
            GroupDTO result = groupService.createGroup(req);
            log.debug("<<< POST /api/groups id={}", result.getId());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("!!! POST /api/groups failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/mine")
    public ResponseEntity<?> getMyGroups() {
        log.trace(">>> GET /api/groups/mine");
        try {
            List<GroupDTO> result = groupService.getMyGroups();
            log.debug("<<< GET /api/groups/mine count={}", result.size());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("!!! GET /api/groups/mine failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getGroup(@PathVariable Long id) {
        log.trace(">>> GET /api/groups/{}", id);
        try {
            GroupDTO result = groupService.getGroupById(id);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("!!! GET /api/groups/{} failed: {}", id, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/join/{token}")
    public ResponseEntity<?> getGroupByToken(@PathVariable String token) {
        log.trace(">>> GET /api/groups/join/[token]");
        try {
            GroupDTO result = groupService.getGroupByInviteToken(token);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.warn("!!! GET /api/groups/join/[token] failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/join/{token}")
    public ResponseEntity<?> joinGroup(@PathVariable String token) {
        log.trace(">>> POST /api/groups/join/[token]");
        try {
            GroupDTO result = groupService.joinGroup(token);
            log.debug("<<< POST /api/groups/join/[token] groupId={}", result.getId());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("!!! POST /api/groups/join/[token] failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteGroup(@PathVariable Long id) {
        log.trace(">>> DELETE /api/groups/{}", id);
        try {
            groupService.deleteGroup(id);
            log.debug("<<< DELETE /api/groups/{} ok", id);
            return ResponseEntity.ok(Map.of("message", "Group deleted"));
        } catch (Exception e) {
            log.error("!!! DELETE /api/groups/{} failed: {}", id, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}/receipts")
    public ResponseEntity<?> getGroupReceipts(@PathVariable Long id) {
        log.trace(">>> GET /api/groups/{}/receipts", id);
        try {
            return ResponseEntity.ok(groupService.getGroupReceipts(id));
        } catch (Exception e) {
            log.error("!!! GET /api/groups/{}/receipts failed: {}", id, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
