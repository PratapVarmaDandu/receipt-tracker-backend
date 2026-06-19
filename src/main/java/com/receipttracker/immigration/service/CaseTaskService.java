package com.receipttracker.immigration.service;

import com.receipttracker.immigration.dto.CaseTaskDTO;
import com.receipttracker.immigration.dto.CreateCaseTaskRequest;
import com.receipttracker.immigration.dto.UpdateCaseTaskRequest;
import com.receipttracker.immigration.model.*;
import com.receipttracker.immigration.repository.*;
import com.receipttracker.model.User;
import com.receipttracker.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class CaseTaskService {

    private static final Logger log = LoggerFactory.getLogger(CaseTaskService.class);

    @Autowired private CaseTaskRepository taskRepo;
    @Autowired private ImmigrationCaseRepository caseRepo;
    @Autowired private ImmOrgMemberRepository memberRepo;
    @Autowired private PermissionService permissionService;
    @Autowired private UserRepository userRepo;

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        OAuth2User principal = (OAuth2User) auth.getPrincipal();
        String googleId = principal.getAttribute("sub");
        return userRepo.findByGoogleId(googleId)
                .orElseThrow(() -> new RuntimeException("Authenticated user not found"));
    }

    @Transactional(readOnly = true)
    public List<CaseTaskDTO> list(Long caseId) {
        log.info(">>> tasks.list() caseId={}", caseId);
        User caller = currentUser();
        permissionService.requireAccess(caller, caseId, GrantScope.READ_CASE);
        ImmigrationCase c = requireCase(caseId);
        return taskRepo.findByImmigrationCaseOrderByDueDateAscCreatedAtAsc(c)
                .stream().map(this::toDTO).toList();
    }

    @Transactional
    public CaseTaskDTO create(Long caseId, CreateCaseTaskRequest req) {
        log.info(">>> tasks.create() caseId={} title={}", caseId, req.title());
        User caller = currentUser();
        permissionService.requireAccess(caller, caseId, GrantScope.WRITE_CASE);

        if (req.title() == null || req.title().isBlank()) {
            throw new RuntimeException("Task title is required");
        }
        ImmigrationCase c = requireCase(caseId);

        CaseTask task = new CaseTask();
        task.setImmigrationCase(c);
        task.setTitle(req.title().trim());
        task.setDescription(req.description());
        task.setDueDate(req.dueDate());
        task.setAssignedToMemberId(req.assignedToMemberId());
        task.setRequired(req.isRequired());
        task.setCreatedByUserId(caller.getId());

        CaseTask saved = taskRepo.save(task);
        log.info("<<< tasks.create() taskId={}", saved.getId());
        return toDTO(saved);
    }

    @Transactional
    public CaseTaskDTO update(Long caseId, Long taskId, UpdateCaseTaskRequest req) {
        log.info(">>> tasks.update() caseId={} taskId={}", caseId, taskId);
        User caller = currentUser();
        permissionService.requireAccess(caller, caseId, GrantScope.WRITE_CASE);

        CaseTask task = requireTask(caseId, taskId);
        if (req.title() != null && !req.title().isBlank()) task.setTitle(req.title().trim());
        if (req.description() != null) task.setDescription(req.description());
        if (req.dueDate() != null) task.setDueDate(req.dueDate());
        if (req.assignedToMemberId() != null) task.setAssignedToMemberId(req.assignedToMemberId());
        if (req.isRequired() != null) task.setRequired(req.isRequired());

        return toDTO(taskRepo.save(task));
    }

    @Transactional
    public CaseTaskDTO complete(Long caseId, Long taskId) {
        log.info(">>> tasks.complete() caseId={} taskId={}", caseId, taskId);
        User caller = currentUser();
        permissionService.requireAccess(caller, caseId, GrantScope.WRITE_CASE);

        CaseTask task = requireTask(caseId, taskId);
        if (task.getCompletedAt() != null) {
            throw new RuntimeException("Task is already completed");
        }
        task.setCompletedAt(LocalDateTime.now());
        task.setCompletedByUserId(caller.getId());

        return toDTO(taskRepo.save(task));
    }

    @Transactional
    public void delete(Long caseId, Long taskId) {
        log.info(">>> tasks.delete() caseId={} taskId={}", caseId, taskId);
        User caller = currentUser();
        permissionService.requireAccess(caller, caseId, GrantScope.WRITE_CASE);
        CaseTask task = requireTask(caseId, taskId);
        taskRepo.delete(task);
        log.info("<<< tasks.delete() taskId={}", taskId);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ImmigrationCase requireCase(Long caseId) {
        return caseRepo.findById(caseId)
                .orElseThrow(() -> new RuntimeException("Case not found: " + caseId));
    }

    private CaseTask requireTask(Long caseId, Long taskId) {
        CaseTask task = taskRepo.findById(taskId)
                .orElseThrow(() -> new RuntimeException("Task not found: " + taskId));
        if (!task.getImmigrationCase().getId().equals(caseId)) {
            throw new RuntimeException("Task " + taskId + " does not belong to case " + caseId);
        }
        return task;
    }

    private String memberName(Long memberId) {
        if (memberId == null) return null;
        return memberRepo.findById(memberId)
                .flatMap(m -> m.getUserId() != null ? userRepo.findById(m.getUserId()) : java.util.Optional.empty())
                .map(User::getName)
                .orElse(null);
    }

    private String userName(Long userId) {
        if (userId == null) return null;
        return userRepo.findById(userId).map(User::getName).orElse(null);
    }

    private CaseTaskDTO toDTO(CaseTask t) {
        boolean overdue = t.getCompletedAt() == null
                && t.getDueDate() != null
                && t.getDueDate().isBefore(LocalDate.now());
        return new CaseTaskDTO(
                t.getId(),
                t.getImmigrationCase().getId(),
                t.getTitle(),
                t.getDescription(),
                t.getDueDate(),
                t.getAssignedToMemberId(),
                memberName(t.getAssignedToMemberId()),
                t.getCompletedAt(),
                t.getCompletedByUserId(),
                userName(t.getCompletedByUserId()),
                t.isRequired(),
                t.getCreatedByUserId(),
                userName(t.getCreatedByUserId()),
                t.getCreatedAt(),
                t.getUpdatedAt(),
                overdue
        );
    }
}
