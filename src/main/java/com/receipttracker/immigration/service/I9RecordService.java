package com.receipttracker.immigration.service;

import com.receipttracker.immigration.dto.CreateI9RecordRequest;
import com.receipttracker.immigration.dto.I9RecordDTO;
import com.receipttracker.immigration.model.I9Record;
import com.receipttracker.immigration.model.ImmOrgMemberStatus;
import com.receipttracker.immigration.repository.I9RecordRepository;
import com.receipttracker.immigration.repository.ImmOrgMemberRepository;
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
import java.util.List;

@Service
public class I9RecordService {

    private static final Logger log = LoggerFactory.getLogger(I9RecordService.class);

    @Autowired private I9RecordRepository i9Repo;
    @Autowired private ImmOrgMemberRepository memberRepo;
    @Autowired private UserRepository userRepo;

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        OAuth2User principal = (OAuth2User) auth.getPrincipal();
        String googleId = principal.getAttribute("sub");
        return userRepo.findByGoogleId(googleId)
                .orElseThrow(() -> new RuntimeException("Authenticated user not found"));
    }

    private void requireActiveMember(User user, Long orgId) {
        boolean ok = memberRepo.findByUserIdAndStatus(user.getId(), ImmOrgMemberStatus.ACTIVE)
                .stream().anyMatch(m -> m.getImmOrgId().equals(orgId));
        if (!ok) throw new RuntimeException("Access denied: not an active member of org " + orgId);
    }

    @Transactional
    public I9RecordDTO create(Long orgId, CreateI9RecordRequest req) {
        log.info(">>> i9.create() orgId={} email={}", orgId, req.employeeEmail());
        User caller = currentUser();
        requireActiveMember(caller, orgId);

        I9Record rec = new I9Record();
        rec.setEmployerImmOrgId(orgId);
        rec.setEmployeeEmail(req.employeeEmail());
        rec.setEmployeeName(req.employeeName());
        rec.setWorkAuthType(req.workAuthType());
        rec.setDocumentTitle(req.documentTitle());
        rec.setDocumentNumber(req.documentNumber());
        if (req.expiryDate() != null && !req.expiryDate().isBlank())
            rec.setExpiryDate(LocalDate.parse(req.expiryDate()));
        if (req.verifiedAt() != null && !req.verifiedAt().isBlank())
            rec.setVerifiedAt(LocalDate.parse(req.verifiedAt()));
        if (req.reverificationDue() != null && !req.reverificationDue().isBlank())
            rec.setReverificationDue(LocalDate.parse(req.reverificationDue()));

        I9Record saved = i9Repo.save(rec);
        log.info("<<< i9.create() id={}", saved.getId());
        return toDTO(saved);
    }

    @Transactional(readOnly = true)
    public List<I9RecordDTO> list(Long orgId) {
        log.info(">>> i9.list() orgId={}", orgId);
        User caller = currentUser();
        requireActiveMember(caller, orgId);
        return i9Repo.findByEmployerImmOrgIdOrderByCreatedAtDesc(orgId)
                .stream().map(this::toDTO).toList();
    }

    @Transactional
    public I9RecordDTO update(Long orgId, Long recordId, CreateI9RecordRequest req) {
        log.info(">>> i9.update() orgId={} recordId={}", orgId, recordId);
        User caller = currentUser();
        requireActiveMember(caller, orgId);

        I9Record rec = i9Repo.findById(recordId)
                .orElseThrow(() -> new RuntimeException("I9 record not found: " + recordId));
        if (!rec.getEmployerImmOrgId().equals(orgId))
            throw new RuntimeException("Record does not belong to org " + orgId);

        if (req.employeeEmail() != null) rec.setEmployeeEmail(req.employeeEmail());
        if (req.employeeName() != null) rec.setEmployeeName(req.employeeName());
        if (req.workAuthType() != null) rec.setWorkAuthType(req.workAuthType());
        if (req.documentTitle() != null) rec.setDocumentTitle(req.documentTitle());
        if (req.documentNumber() != null) rec.setDocumentNumber(req.documentNumber());
        rec.setExpiryDate(req.expiryDate() != null && !req.expiryDate().isBlank()
                ? LocalDate.parse(req.expiryDate()) : null);
        rec.setVerifiedAt(req.verifiedAt() != null && !req.verifiedAt().isBlank()
                ? LocalDate.parse(req.verifiedAt()) : null);
        rec.setReverificationDue(req.reverificationDue() != null && !req.reverificationDue().isBlank()
                ? LocalDate.parse(req.reverificationDue()) : null);

        I9Record saved = i9Repo.save(rec);
        log.info("<<< i9.update() id={}", saved.getId());
        return toDTO(saved);
    }

    @Transactional(readOnly = true)
    public List<I9RecordDTO> listExpiring(Long orgId, int days) {
        log.info(">>> i9.listExpiring() orgId={} days={}", orgId, days);
        User caller = currentUser();
        requireActiveMember(caller, orgId);
        LocalDate cutoff = LocalDate.now().plusDays(days);
        return i9Repo.findByEmployerImmOrgIdAndExpiryDateBetween(orgId, LocalDate.now(), cutoff)
                .stream().map(this::toDTO).toList();
    }

    private I9RecordDTO toDTO(I9Record r) {
        return new I9RecordDTO(
                r.getId(), r.getEmployerImmOrgId(), r.getEmployeeEmail(),
                r.getEmployeeName(), r.getWorkAuthType(), r.getDocumentTitle(),
                r.getDocumentNumber(), r.getExpiryDate(), r.getVerifiedAt(),
                r.getReverificationDue(), r.getStatus(), r.getCreatedAt(), r.getUpdatedAt()
        );
    }
}
