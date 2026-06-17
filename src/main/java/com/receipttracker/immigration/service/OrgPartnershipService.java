package com.receipttracker.immigration.service;

import com.receipttracker.immigration.dto.CreatePartnershipRequest;
import com.receipttracker.immigration.dto.OrgPartnershipDTO;
import com.receipttracker.immigration.model.*;
import com.receipttracker.immigration.repository.ImmOrgMemberRepository;
import com.receipttracker.immigration.repository.ImmOrgRepository;
import com.receipttracker.immigration.repository.OrgPartnershipRepository;
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

import java.util.ArrayList;
import java.util.List;

@Service
public class OrgPartnershipService {

    private static final Logger log = LoggerFactory.getLogger(OrgPartnershipService.class);

    @Autowired private OrgPartnershipRepository partnershipRepo;
    @Autowired private ImmOrgRepository immOrgRepo;
    @Autowired private ImmOrgMemberRepository immOrgMemberRepo;
    @Autowired private UserRepository userRepo;

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        OAuth2User principal = (OAuth2User) auth.getPrincipal();
        String googleId = principal.getAttribute("sub");
        return userRepo.findByGoogleId(googleId)
                .orElseThrow(() -> new RuntimeException("Authenticated user not found"));
    }

    @Transactional
    public OrgPartnershipDTO create(CreatePartnershipRequest req) {
        log.info(">>> create() employerOrgId={} lawFirmOrgId={}", req.employerOrgId(), req.lawFirmOrgId());
        User user = currentUser();

        immOrgRepo.findById(req.employerOrgId())
                .filter(o -> o.getOrgType() == ImmOrgType.EMPLOYER)
                .orElseThrow(() -> new RuntimeException("Employer org not found or wrong type: " + req.employerOrgId()));
        immOrgRepo.findById(req.lawFirmOrgId())
                .filter(o -> o.getOrgType() == ImmOrgType.LAW_FIRM)
                .orElseThrow(() -> new RuntimeException("Law firm org not found or wrong type: " + req.lawFirmOrgId()));

        boolean isOwnerOfEither = isActiveMember(user.getId(), req.employerOrgId())
                || isActiveMember(user.getId(), req.lawFirmOrgId());
        if (!isOwnerOfEither) {
            throw new RuntimeException("Access denied: must be a member of one of the orgs to initiate partnership");
        }

        OrgPartnership p = new OrgPartnership();
        p.setEmployerOrgId(req.employerOrgId());
        p.setLawFirmOrgId(req.lawFirmOrgId());
        p.setStatus(OrgPartnershipStatus.PENDING);
        p.setInitiatedByUserId(user.getId());
        OrgPartnership saved = partnershipRepo.save(p);
        log.info("<<< create() partnershipId={}", saved.getId());
        return enrichDTO(saved);
    }

    @Transactional(readOnly = true)
    public List<OrgPartnershipDTO> listMine() {
        User user = currentUser();
        List<Long> myOrgIds = immOrgMemberRepo.findByUserIdAndStatus(user.getId(), ImmOrgMemberStatus.ACTIVE)
                .stream().map(ImmOrgMember::getImmOrgId).toList();

        List<OrgPartnership> result = new ArrayList<>();
        for (Long orgId : myOrgIds) {
            result.addAll(partnershipRepo.findByEmployerOrgId(orgId));
            result.addAll(partnershipRepo.findByLawFirmOrgId(orgId));
        }
        return result.stream().distinct().map(this::enrichDTO).toList();
    }

    @Transactional
    public OrgPartnershipDTO accept(Long id) {
        User user = currentUser();
        OrgPartnership p = partnershipRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Partnership not found: " + id));

        // The org that did NOT initiate must accept
        Long initiatorOrgId = isActiveMember(user.getId(), p.getEmployerOrgId())
                && p.getInitiatedByUserId() != null ? p.getEmployerOrgId() : p.getLawFirmOrgId();
        Long acceptorOrgId = initiatorOrgId.equals(p.getEmployerOrgId()) ? p.getLawFirmOrgId() : p.getEmployerOrgId();

        if (!isActiveMember(user.getId(), acceptorOrgId)) {
            throw new RuntimeException("Access denied: must be a member of the other org to accept");
        }

        p.setStatus(OrgPartnershipStatus.ACTIVE);
        return enrichDTO(partnershipRepo.save(p));
    }

    @Transactional
    public void end(Long id) {
        User user = currentUser();
        OrgPartnership p = partnershipRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Partnership not found: " + id));

        boolean isMemberOfEither = isActiveMember(user.getId(), p.getEmployerOrgId())
                || isActiveMember(user.getId(), p.getLawFirmOrgId());
        if (!isMemberOfEither) {
            throw new RuntimeException("Access denied: not a member of either org");
        }

        p.setStatus(OrgPartnershipStatus.ENDED);
        partnershipRepo.save(p);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private boolean isActiveMember(Long userId, Long orgId) {
        return immOrgMemberRepo.findByUserIdAndStatus(userId, ImmOrgMemberStatus.ACTIVE)
                .stream().anyMatch(m -> m.getImmOrgId().equals(orgId));
    }

    OrgPartnershipDTO enrichDTO(OrgPartnership p) {
        String employerName = immOrgRepo.findById(p.getEmployerOrgId())
                .map(ImmOrg::getName).orElse("Unknown");
        String lawFirmName = immOrgRepo.findById(p.getLawFirmOrgId())
                .map(ImmOrg::getName).orElse("Unknown");
        return new OrgPartnershipDTO(p.getId(),
                p.getEmployerOrgId(), employerName,
                p.getLawFirmOrgId(), lawFirmName,
                p.getStatus().name(), p.getCreatedAt().toString());
    }
}
