package com.receipttracker.immigration.service;

import com.receipttracker.immigration.dto.ConsentRecordDTO;
import com.receipttracker.immigration.model.*;
import com.receipttracker.immigration.repository.BeneficiaryRepository;
import com.receipttracker.immigration.repository.ConsentRecordRepository;
import com.receipttracker.immigration.repository.GrantRepository;
import com.receipttracker.immigration.repository.ImmigrationCaseRepository;
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

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ConsentService {

    private static final Logger log = LoggerFactory.getLogger(ConsentService.class);

    @Autowired private ConsentRecordRepository consentRepo;
    @Autowired private GrantRepository grantRepo;
    @Autowired private ImmigrationCaseRepository caseRepo;
    @Autowired private BeneficiaryRepository beneficiaryRepo;
    @Autowired private PermissionService permissionService;
    @Autowired private AuditService auditService;
    @Autowired private UserRepository userRepo;

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        OAuth2User principal = (OAuth2User) auth.getPrincipal();
        String googleId = principal.getAttribute("sub");
        return userRepo.findByGoogleId(googleId)
                .orElseThrow(() -> new RuntimeException("Authenticated user not found"));
    }

    @Transactional(readOnly = true)
    public List<ConsentRecordDTO> listForCase(Long caseId) {
        User user = currentUser();
        permissionService.requireAccess(user, caseId, GrantScope.READ_CASE);
        ImmigrationCase c = caseRepo.findById(caseId)
                .orElseThrow(() -> new RuntimeException("Case not found: " + caseId));
        return consentRepo.findByImmigrationCaseOrderByActionAtDesc(c)
                .stream().map(this::toDTO).toList();
    }

    /**
     * Grant consent. Only the beneficiary of the case may call this.
     */
    @Transactional
    public ConsentRecordDTO grantConsent(Long caseId, String relationshipStr, String notes) {
        return record(caseId, relationshipStr, true, notes);
    }

    /**
     * Revoke consent. Only the beneficiary of the case may call this.
     * Side-effect: revokes all active Grants for that relationship on the case.
     */
    @Transactional
    public ConsentRecordDTO revokeConsent(Long caseId, String relationshipStr, String notes) {
        User user = currentUser();
        CaseRelationship relationship = parseRelationship(relationshipStr);

        ImmigrationCase c = caseRepo.findById(caseId)
                .orElseThrow(() -> new RuntimeException("Case not found: " + caseId));

        requireBeneficiary(user, c);

        // Revoke all active Grants for this relationship on the case
        grantRepo.findByImmigrationCaseAndRevokedAtIsNull(c).stream()
                .filter(g -> g.getRelationship() == relationship)
                .forEach(g -> {
                    g.setRevokedAt(LocalDateTime.now());
                    grantRepo.save(g);
                    log.info("Consent revocation: revoked grant {} for relationship {}", g.getId(), relationship);
                });

        ConsentRecordDTO dto = record(caseId, relationshipStr, false, notes);
        auditService.append(c, user, "CONSENT_REVOKED",
                "{\"relationship\":\"" + relationship.name() + "\"}",
                FeedVisibility.BENEFICIARY_ONLY);
        return dto;
    }

    private ConsentRecordDTO record(Long caseId, String relationshipStr, boolean granted, String notes) {
        User user = currentUser();
        CaseRelationship relationship = parseRelationship(relationshipStr);

        ImmigrationCase c = caseRepo.findById(caseId)
                .orElseThrow(() -> new RuntimeException("Case not found: " + caseId));
        requireBeneficiary(user, c);

        Beneficiary beneficiary = beneficiaryRepo.findByUser(user)
                .orElseThrow(() -> new RuntimeException("Beneficiary record not found"));

        ConsentRecord rec = new ConsentRecord();
        rec.setImmigrationCase(c);
        rec.setBeneficiary(beneficiary);
        rec.setCoversRelationship(relationship);
        rec.setGranted(granted);
        rec.setNotes(notes);
        rec.setActionAt(LocalDateTime.now());
        return toDTO(consentRepo.save(rec));
    }

    private void requireBeneficiary(User user, ImmigrationCase c) {
        if (!c.getBeneficiary().getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Only the beneficiary can manage consent for this case");
        }
    }

    private CaseRelationship parseRelationship(String s) {
        if (s == null || s.isBlank()) throw new RuntimeException("relationship is required");
        try { return CaseRelationship.valueOf(s); }
        catch (IllegalArgumentException e) { throw new RuntimeException("Unknown relationship: " + s); }
    }

    ConsentRecordDTO toDTO(ConsentRecord r) {
        return new ConsentRecordDTO(r.getId(), r.getImmigrationCase().getId(),
                r.getCoversRelationship().name(), r.isGranted(), r.getActionAt(), r.getNotes());
    }
}
