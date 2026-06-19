package com.receipttracker.immigration.service;

import com.receipttracker.immigration.dto.CapSeasonSummaryDTO;
import com.receipttracker.immigration.dto.CreateH1bCapRequest;
import com.receipttracker.immigration.dto.H1bCapRegistrationDTO;
import com.receipttracker.immigration.dto.LotteryResultRequest;
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

import java.time.Year;
import java.util.List;
import java.util.Optional;

@Service
public class H1bCapService {

    private static final Logger log = LoggerFactory.getLogger(H1bCapService.class);

    @Autowired private H1bCapRegistrationRepository capRepo;
    @Autowired private ImmigrationCaseRepository caseRepo;
    @Autowired private ImmOrgMemberRepository memberRepo;
    @Autowired private ImmOrgRepository orgRepo;
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
    public Optional<H1bCapRegistrationDTO> get(Long caseId) {
        log.info(">>> h1bCap.get() caseId={}", caseId);
        User caller = currentUser();
        permissionService.requireAccess(caller, caseId, GrantScope.READ_CASE);
        ImmigrationCase c = requireCase(caseId);
        return capRepo.findByImmigrationCase(c).map(this::toDTO);
    }

    @Transactional
    public H1bCapRegistrationDTO create(Long caseId, CreateH1bCapRequest req) {
        log.info(">>> h1bCap.create() caseId={} year={}", caseId, req.registrationYear());
        User caller = currentUser();
        permissionService.requireAccess(caller, caseId, GrantScope.WRITE_CASE);
        requireAttorneyInFirm(caller, caseId);

        ImmigrationCase c = requireCase(caseId);
        if (c.getCaseType() != CaseType.H1B_INITIAL) {
            throw new RuntimeException("H-1B cap registration only applies to H1B_INITIAL cases");
        }
        if (capRepo.findByImmigrationCase(c).isPresent()) {
            throw new RuntimeException("A cap registration already exists for this case. Use PUT to update.");
        }
        if (req.registrationDate() == null) {
            throw new RuntimeException("registrationDate is required");
        }

        H1bCapRegistration reg = new H1bCapRegistration();
        reg.setImmigrationCase(c);
        reg.setRegistrationYear(req.registrationYear());
        reg.setRegistrationNumber(req.registrationNumber());
        reg.setRegistrationDate(req.registrationDate());

        H1bCapRegistration saved = capRepo.save(reg);
        log.info("<<< h1bCap.create() regId={}", saved.getId());
        return toDTO(saved);
    }

    @Transactional
    public H1bCapRegistrationDTO updateLotteryResult(Long caseId, LotteryResultRequest req) {
        log.info(">>> h1bCap.updateLotteryResult() caseId={} selected={}", caseId, req.selectedInLottery());
        User caller = currentUser();
        permissionService.requireAccess(caller, caseId, GrantScope.WRITE_CASE);
        requireAttorneyInFirm(caller, caseId);

        ImmigrationCase c = requireCase(caseId);
        H1bCapRegistration reg = capRepo.findByImmigrationCase(c)
                .orElseThrow(() -> new RuntimeException("No cap registration found for case " + caseId));

        reg.setSelectedInLottery(req.selectedInLottery());
        reg.setSelectionDate(req.selectionDate());

        H1bCapRegistration saved = capRepo.save(reg);
        log.info("<<< h1bCap.updateLotteryResult() regId={}", saved.getId());
        return toDTO(saved);
    }

    @Transactional(readOnly = true)
    public CapSeasonSummaryDTO getCapSeasonSummary(Long orgId, Integer year) {
        log.info(">>> h1bCap.getCapSeasonSummary() orgId={} year={}", orgId, year);
        User caller = currentUser();
        requireOrgMember(caller, orgId);

        int targetYear = (year != null) ? year : Year.now().getValue();
        List<H1bCapRegistration> regs = capRepo.findByYearAndLawFirm(targetYear, orgId);

        int total      = regs.size();
        int selected   = (int) regs.stream().filter(r -> Boolean.TRUE.equals(r.getSelectedInLottery())).count();
        int notSel     = (int) regs.stream().filter(r -> Boolean.FALSE.equals(r.getSelectedInLottery())).count();
        int pending    = total - selected - notSel;

        log.info("<<< h1bCap.getCapSeasonSummary() year={} total={}", targetYear, total);
        return new CapSeasonSummaryDTO(targetYear, total, selected, notSel, pending);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ImmigrationCase requireCase(Long caseId) {
        return caseRepo.findById(caseId)
                .orElseThrow(() -> new RuntimeException("Case not found: " + caseId));
    }

    private void requireAttorneyInFirm(User caller, Long caseId) {
        ImmigrationCase c = requireCase(caseId);
        Long lawFirmOrgId = c.getLawFirmImmOrgId();
        if (lawFirmOrgId == null) {
            throw new RuntimeException("Access denied: case has no assigned law firm");
        }
        boolean isAttorneyOrOwner = memberRepo
                .findByUserIdAndStatus(caller.getId(), ImmOrgMemberStatus.ACTIVE)
                .stream()
                .anyMatch(m -> m.getImmOrgId().equals(lawFirmOrgId)
                        && (m.getRole() == ImmOrgMemberRole.ATTORNEY
                         || m.getRole() == ImmOrgMemberRole.OWNER));
        if (!isAttorneyOrOwner) {
            throw new RuntimeException("Access denied: ATTORNEY or OWNER role in the law firm required");
        }
    }

    private void requireOrgMember(User caller, Long orgId) {
        boolean isMember = memberRepo
                .findByUserIdAndStatus(caller.getId(), ImmOrgMemberStatus.ACTIVE)
                .stream()
                .anyMatch(m -> m.getImmOrgId().equals(orgId));
        if (!isMember) {
            throw new RuntimeException("Access denied: not a member of org " + orgId);
        }
    }

    private H1bCapRegistrationDTO toDTO(H1bCapRegistration r) {
        return new H1bCapRegistrationDTO(
                r.getId(),
                r.getImmigrationCase().getId(),
                r.getImmigrationCase().getCaseNumber(),
                r.getRegistrationYear(),
                r.getRegistrationNumber(),
                r.getSelectedInLottery(),
                r.getSelectionDate(),
                r.getRegistrationDate(),
                r.getCreatedAt()
        );
    }
}
