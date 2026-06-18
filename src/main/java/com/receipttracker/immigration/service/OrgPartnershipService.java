package com.receipttracker.immigration.service;

import com.receipttracker.immigration.dto.*;
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
import java.util.UUID;

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

    @Transactional
    public OrgPartnershipDTO inviteEmployer(PartnershipInviteRequest req) {
        User caller = currentUser();
        immOrgRepo.findById(req.lawFirmOrgId())
                .filter(o -> o.getOrgType() == ImmOrgType.LAW_FIRM)
                .orElseThrow(() -> new RuntimeException("Law firm org not found: " + req.lawFirmOrgId()));
        if (!isActiveMember(caller.getId(), req.lawFirmOrgId())) {
            throw new RuntimeException("Access denied: not a member of law firm org " + req.lawFirmOrgId());
        }

        String token = UUID.randomUUID().toString();
        OrgPartnership p = new OrgPartnership();
        p.setLawFirmOrgId(req.lawFirmOrgId());
        p.setInviteEmail(req.employerEmail().toLowerCase().trim());
        p.setInviteToken(token);
        p.setStatus(OrgPartnershipStatus.PENDING);
        p.setInitiatedByUserId(caller.getId());
        OrgPartnership saved = partnershipRepo.save(p);

        log.warn("IMM_EMPLOYER_INVITE lawFirmOrgId={} email={} token={}", req.lawFirmOrgId(), req.employerEmail(), token);
        return enrichDTO(saved);
    }

    @Transactional(readOnly = true)
    public PartnershipJoinInfoDTO getOnboardInfo(String token) {
        OrgPartnership p = partnershipRepo.findByInviteToken(token)
                .orElseThrow(() -> new RuntimeException("Invalid or expired invite link"));
        String lawFirmName = immOrgRepo.findById(p.getLawFirmOrgId())
                .map(ImmOrg::getName).orElse("Unknown");
        return new PartnershipJoinInfoDTO(p.getId(), p.getLawFirmOrgId(), lawFirmName,
                p.getInviteEmail(), p.getStatus().name());
    }

    @Transactional
    public OrgPartnershipDTO completeOnboarding(String token, EmployerOnboardRequest req) {
        User caller = currentUser();

        OrgPartnership p = partnershipRepo.findByInviteToken(token)
                .orElseThrow(() -> new RuntimeException("Invalid or expired invite link"));

        if (!p.getInviteEmail().equalsIgnoreCase(caller.getEmail())) {
            throw new RuntimeException("This invite is not for your email address");
        }
        if (p.getStatus() == OrgPartnershipStatus.ACTIVE) {
            return enrichDTO(p);
        }

        // Reject if caller already belongs to a LAW_FIRM org (cannot be both)
        List<ImmOrg> existingOrgs = immOrgRepo.findByMemberUserId(caller.getId());
        boolean hasConflict = existingOrgs.stream().anyMatch(o -> o.getOrgType() == ImmOrgType.LAW_FIRM);
        if (hasConflict) {
            throw new RuntimeException("You are already registered as a law firm. A user cannot be both an employer and an attorney/law firm.");
        }

        // Reuse existing employer org if caller already has one, otherwise create
        ImmOrg employerOrg = existingOrgs.stream()
                .filter(o -> o.getOrgType() == ImmOrgType.EMPLOYER)
                .findFirst()
                .orElse(null);

        if (employerOrg == null) {
            employerOrg = new ImmOrg();
            employerOrg.setOrgType(ImmOrgType.EMPLOYER);
            employerOrg.setOwnerUserId(caller.getId());
            employerOrg = immOrgRepo.save(employerOrg);

            ImmOrgMember owner = new ImmOrgMember();
            owner.setImmOrgId(employerOrg.getId());
            owner.setUserId(caller.getId());
            owner.setEmail(caller.getEmail());
            owner.setRole(ImmOrgMemberRole.OWNER);
            owner.setStatus(ImmOrgMemberStatus.ACTIVE);
            immOrgMemberRepo.save(owner);
        }

        // Apply employer profile details
        employerOrg.setName(req.orgName());
        employerOrg.setContactName(req.contactName());
        employerOrg.setContactEmail(req.contactEmail());
        employerOrg.setAddress(req.address());
        employerOrg.setCity(req.city());
        employerOrg.setStateCode(req.stateCode());
        employerOrg.setZipCode(req.zipCode());
        employerOrg.setEinNumber(req.einNumber());
        employerOrg.setWebsite(req.website());
        immOrgRepo.save(employerOrg);

        // Activate the partnership
        p.setEmployerOrgId(employerOrg.getId());
        p.setStatus(OrgPartnershipStatus.ACTIVE);
        OrgPartnership saved = partnershipRepo.save(p);
        log.info("<<< completeOnboarding() partnershipId={} employerOrgId={}", saved.getId(), employerOrg.getId());
        return enrichDTO(saved);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private boolean isActiveMember(Long userId, Long orgId) {
        return immOrgMemberRepo.findByUserIdAndStatus(userId, ImmOrgMemberStatus.ACTIVE)
                .stream().anyMatch(m -> m.getImmOrgId().equals(orgId));
    }

    OrgPartnershipDTO enrichDTO(OrgPartnership p) {
        String employerName = p.getEmployerOrgId() != null
                ? immOrgRepo.findById(p.getEmployerOrgId()).map(ImmOrg::getName).orElse("Unknown")
                : "(Pending)";
        String lawFirmName = immOrgRepo.findById(p.getLawFirmOrgId())
                .map(ImmOrg::getName).orElse("Unknown");
        return new OrgPartnershipDTO(p.getId(),
                p.getEmployerOrgId(), employerName,
                p.getLawFirmOrgId(), lawFirmName,
                p.getStatus().name(), p.getCreatedAt().toString(),
                p.getInviteEmail(), p.getInviteToken());
    }
}
