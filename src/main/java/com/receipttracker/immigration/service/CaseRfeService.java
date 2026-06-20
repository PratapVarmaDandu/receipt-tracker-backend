package com.receipttracker.immigration.service;

import com.receipttracker.immigration.dto.CaseRfeDTO;
import com.receipttracker.immigration.dto.CreateRfeRequest;
import com.receipttracker.immigration.dto.UpdateRfeRequest;
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
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class CaseRfeService {

    private static final Logger log = LoggerFactory.getLogger(CaseRfeService.class);

    @Autowired private CaseRfeRepository rfeRepo;
    @Autowired private ImmigrationCaseRepository caseRepo;
    @Autowired private KeyDateRepository keyDateRepo;
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
    public List<CaseRfeDTO> list(Long caseId) {
        log.info(">>> rfe.list() caseId={}", caseId);
        User caller = currentUser();
        permissionService.requireAccess(caller, caseId, GrantScope.READ_CASE);
        ImmigrationCase c = requireCase(caseId);
        return rfeRepo.findByImmigrationCaseOrderByCreatedAtDesc(c)
                .stream().map(this::toDTO).toList();
    }

    @Transactional
    public CaseRfeDTO create(Long caseId, CreateRfeRequest req) {
        log.info(">>> rfe.create() caseId={} category={}", caseId, req.uscisCategory());
        User caller = currentUser();
        permissionService.requireAccess(caller, caseId, GrantScope.WRITE_CASE);
        requireAttorneyInFirm(caller, caseId);

        if (req.issuedDate() == null) throw new RuntimeException("issuedDate is required");

        ImmigrationCase c = requireCase(caseId);

        CaseRfe rfe = new CaseRfe();
        rfe.setImmigrationCase(c);
        rfe.setIssuedDate(req.issuedDate());
        LocalDate deadline = req.responseDeadline() != null
                ? req.responseDeadline()
                : req.issuedDate().plusDays(87);
        rfe.setResponseDeadline(deadline);
        rfe.setUscisCategory(req.uscisCategory());
        rfe.setUscisNote(req.uscisNote());
        rfe.setStatus("OPEN");
        rfe.setCreatedByUserId(caller.getId());
        CaseRfe saved = rfeRepo.save(rfe);

        // Auto-create a PETITION_DEADLINE key date for the response deadline
        KeyDate kd = keyDateRepo.findByImmigrationCaseAndDateType(c, KeyDateType.PETITION_DEADLINE)
                .orElseGet(() -> {
                    KeyDate k = new KeyDate();
                    k.setImmigrationCase(c);
                    k.setDateType(KeyDateType.PETITION_DEADLINE);
                    return k;
                });
        kd.setDate(deadline);
        kd.setLabel("RFE Response Deadline");
        kd.setAutoComputed(false);
        keyDateRepo.save(kd);

        log.info("<<< rfe.create() rfeId={} deadline={}", saved.getId(), deadline);
        return toDTO(saved);
    }

    @Transactional
    public CaseRfeDTO update(Long caseId, Long rfeId, UpdateRfeRequest req) {
        log.info(">>> rfe.update() caseId={} rfeId={}", caseId, rfeId);
        User caller = currentUser();
        permissionService.requireAccess(caller, caseId, GrantScope.WRITE_CASE);
        requireAttorneyInFirm(caller, caseId);

        CaseRfe rfe = requireRfe(caseId, rfeId);
        if (req.issuedDate() != null) rfe.setIssuedDate(req.issuedDate());
        if (req.responseDeadline() != null) rfe.setResponseDeadline(req.responseDeadline());
        if (req.uscisCategory() != null) rfe.setUscisCategory(req.uscisCategory());
        if (req.uscisNote() != null) rfe.setUscisNote(req.uscisNote());
        if (req.status() != null) rfe.setStatus(req.status());

        return toDTO(rfeRepo.save(rfe));
    }

    @Transactional
    public CaseRfeDTO respond(Long caseId, Long rfeId) {
        log.info(">>> rfe.respond() caseId={} rfeId={}", caseId, rfeId);
        User caller = currentUser();
        permissionService.requireAccess(caller, caseId, GrantScope.WRITE_CASE);
        requireAttorneyInFirm(caller, caseId);

        CaseRfe rfe = requireRfe(caseId, rfeId);
        if ("RESPONDED".equals(rfe.getStatus()))
            throw new RuntimeException("RFE already marked as responded");
        rfe.setStatus("RESPONDED");
        rfe.setRespondedAt(LocalDateTime.now());
        return toDTO(rfeRepo.save(rfe));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void requireAttorneyInFirm(User caller, Long caseId) {
        ImmigrationCase c = requireCase(caseId);
        if (c.getLawFirmImmOrgId() == null) return;
        boolean ok = memberRepo.findByUserIdAndStatus(caller.getId(), ImmOrgMemberStatus.ACTIVE)
                .stream()
                .anyMatch(m -> m.getImmOrgId().equals(c.getLawFirmImmOrgId())
                        && (m.getRole() == ImmOrgMemberRole.ATTORNEY || m.getRole() == ImmOrgMemberRole.OWNER));
        if (!ok) throw new RuntimeException("Access denied: ATTORNEY role in law firm required for RFE write");
    }

    private ImmigrationCase requireCase(Long caseId) {
        return caseRepo.findById(caseId)
                .orElseThrow(() -> new RuntimeException("Case not found: " + caseId));
    }

    private CaseRfe requireRfe(Long caseId, Long rfeId) {
        CaseRfe rfe = rfeRepo.findById(rfeId)
                .orElseThrow(() -> new RuntimeException("RFE not found: " + rfeId));
        if (!rfe.getImmigrationCase().getId().equals(caseId))
            throw new RuntimeException("RFE does not belong to case " + caseId);
        return rfe;
    }

    CaseRfeDTO toDTO(CaseRfe rfe) {
        long daysUntil = ChronoUnit.DAYS.between(LocalDate.now(), rfe.getResponseDeadline());
        return new CaseRfeDTO(
                rfe.getId(),
                rfe.getImmigrationCase().getId(),
                rfe.getIssuedDate(),
                rfe.getResponseDeadline(),
                rfe.getUscisCategory(),
                rfe.getUscisNote(),
                rfe.getStatus(),
                rfe.getRespondedAt(),
                rfe.getCreatedByUserId(),
                rfe.getCreatedAt(),
                daysUntil
        );
    }
}
