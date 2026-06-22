package com.receipttracker.immigration.service;

import com.receipttracker.immigration.dto.CreateImmOrgRequest;
import com.receipttracker.immigration.dto.ImmOrgDTO;
import com.receipttracker.immigration.dto.ImmOrgMemberDTO;
import com.receipttracker.immigration.dto.InviteMemberRequest;
import com.receipttracker.immigration.dto.UpdateImmOrgRequest;
import com.receipttracker.immigration.model.*;
import com.receipttracker.immigration.repository.ImmOrgMemberRepository;
import com.receipttracker.immigration.repository.ImmOrgRepository;
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
import java.util.Set;
import java.util.UUID;

@Service
public class ImmOrgService {

    private static final Logger log = LoggerFactory.getLogger(ImmOrgService.class);

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
    public ImmOrgDTO create(CreateImmOrgRequest req) {
        log.info(">>> create() name={} orgType={}", req.name(), req.orgType());
        User user = currentUser();

        rejectHtml("name", req.name());

        ImmOrgType orgType;
        try {
            orgType = ImmOrgType.valueOf(req.orgType());
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Unknown orgType: " + req.orgType());
        }

        // A user cannot belong to both an EMPLOYER org and a LAW_FIRM org
        List<ImmOrg> existingOrgs = immOrgRepo.findByMemberUserId(user.getId());
        boolean hasConflict = existingOrgs.stream()
                .anyMatch(o -> o.getOrgType() != orgType);
        if (hasConflict) {
            ImmOrgType existing = existingOrgs.stream()
                    .map(ImmOrg::getOrgType).findFirst().orElse(null);
            throw new RuntimeException(
                "You are already registered as a " + (existing != null ? existing.name().toLowerCase().replace("_", " ") : "different org type") +
                ". A user cannot be both an employer and an attorney/law firm.");
        }

        ImmOrg org = new ImmOrg();
        org.setName(req.name());
        org.setOrgType(orgType);
        org.setOwnerUserId(user.getId());
        ImmOrg saved = immOrgRepo.save(org);

        ImmOrgMember ownerMember = new ImmOrgMember();
        ownerMember.setImmOrgId(saved.getId());
        ownerMember.setUserId(user.getId());
        ownerMember.setEmail(user.getEmail());
        ownerMember.setRole(ImmOrgMemberRole.OWNER);
        ownerMember.setStatus(ImmOrgMemberStatus.ACTIVE);
        immOrgMemberRepo.save(ownerMember);

        log.info("<<< create() orgId={}", saved.getId());
        return toDTO(saved);
    }

    @Transactional(readOnly = true)
    public List<ImmOrgDTO> listMine() {
        User user = currentUser();
        List<ImmOrgMember> myMemberships = immOrgMemberRepo.findByUserIdAndStatus(user.getId(), ImmOrgMemberStatus.ACTIVE);
        List<ImmOrg> orgs = new ArrayList<>(immOrgRepo.findByMemberUserId(user.getId()));
        return orgs.stream().map(o -> {
            Long memberId = myMemberships.stream()
                    .filter(m -> m.getImmOrgId().equals(o.getId()))
                    .findFirst().map(ImmOrgMember::getId).orElse(null);
            return toDTO(o, memberId);
        }).toList();
    }

    @Transactional(readOnly = true)
    public ImmOrgDTO getById(Long id) {
        User user = currentUser();
        requireActiveMember(user, id);
        return immOrgRepo.findById(id)
                .map(this::toDTO)
                .orElseThrow(() -> new RuntimeException("Org not found: " + id));
    }

    private static final Set<ImmOrgMemberRole> INVITABLE_ROLES =
            Set.of(ImmOrgMemberRole.ATTORNEY, ImmOrgMemberRole.PARALEGAL, ImmOrgMemberRole.CASE_VIEWER);

    @Transactional
    public ImmOrgMemberDTO inviteMember(Long orgId, InviteMemberRequest req) {
        User caller = currentUser();
        requireOwner(caller, orgId);

        ImmOrgMemberRole assignedRole;
        try {
            assignedRole = ImmOrgMemberRole.valueOf(req.role() != null ? req.role().trim() : "");
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Invalid role '" + req.role()
                    + "'. Must be one of: ATTORNEY, PARALEGAL, CASE_VIEWER");
        }
        if (!INVITABLE_ROLES.contains(assignedRole)) {
            throw new RuntimeException("Role must be one of: ATTORNEY, PARALEGAL, CASE_VIEWER");
        }

        // Idempotent: if already a member, return existing
        var existing = immOrgMemberRepo.findByEmailAndImmOrgId(req.email(), orgId);
        if (existing.isPresent() && existing.get().getStatus() != ImmOrgMemberStatus.REMOVED) {
            return toMemberDTO(existing.get());
        }

        String token = UUID.randomUUID().toString();
        ImmOrgMember member = new ImmOrgMember();
        member.setImmOrgId(orgId);
        member.setEmail(req.email());
        member.setRole(assignedRole);
        member.setStatus(ImmOrgMemberStatus.PENDING);
        member.setInviteToken(token);
        ImmOrgMember saved = immOrgMemberRepo.save(member);

        log.warn("IMM_ORG_INVITE orgId={} email={} role={} token={}", orgId, req.email(), assignedRole, token);
        return toMemberDTO(saved);
    }

    @Transactional(readOnly = true)
    public List<ImmOrgMemberDTO> listMembers(Long orgId) {
        User caller = currentUser();
        requireActiveMember(caller, orgId);
        return immOrgMemberRepo.findByImmOrgId(orgId).stream().map(this::toMemberDTO).toList();
    }

    @Transactional
    public void removeMember(Long orgId, Long memberId) {
        User caller = currentUser();
        requireOwner(caller, orgId);
        ImmOrgMember member = immOrgMemberRepo.findById(memberId)
                .orElseThrow(() -> new RuntimeException("Member not found: " + memberId));
        if (!member.getImmOrgId().equals(orgId)) {
            throw new RuntimeException("Member does not belong to org " + orgId);
        }
        member.setStatus(ImmOrgMemberStatus.REMOVED);
        immOrgMemberRepo.save(member);
    }

    @Transactional(readOnly = true)
    public ImmOrgMemberDTO getJoinInfo(String token) {
        return immOrgMemberRepo.findByInviteToken(token)
                .map(this::toMemberDTO)
                .orElseThrow(() -> new RuntimeException("Invalid or expired invite token"));
    }

    @Transactional
    public ImmOrgMemberDTO acceptInvite(String token) {
        User caller = currentUser();
        ImmOrgMember member = immOrgMemberRepo.findByInviteToken(token)
                .orElseThrow(() -> new RuntimeException("Invalid or expired invite token"));

        if (!member.getEmail().equalsIgnoreCase(caller.getEmail())) {
            throw new RuntimeException("This invite is not for your account");
        }
        if (member.getStatus() == ImmOrgMemberStatus.ACTIVE) {
            return toMemberDTO(member);
        }

        member.setStatus(ImmOrgMemberStatus.ACTIVE);
        member.setUserId(caller.getId());
        return toMemberDTO(immOrgMemberRepo.save(member));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void requireActiveMember(User user, Long orgId) {
        boolean ok = immOrgMemberRepo.findByUserIdAndStatus(user.getId(), ImmOrgMemberStatus.ACTIVE)
                .stream().anyMatch(m -> m.getImmOrgId().equals(orgId));
        if (!ok) throw new RuntimeException("Access denied: not a member of org " + orgId);
    }

    private void requireOwner(User user, Long orgId) {
        boolean ok = immOrgMemberRepo.findByImmOrgId(orgId).stream()
                .anyMatch(m -> m.getUserId() != null
                        && m.getUserId().equals(user.getId())
                        && m.getRole() == ImmOrgMemberRole.OWNER
                        && m.getStatus() == ImmOrgMemberStatus.ACTIVE);
        if (!ok) throw new RuntimeException("Access denied: must be OWNER of org " + orgId);
    }

    private ImmOrgDTO toDTO(ImmOrg o) {
        return toDTO(o, null);
    }

    @Transactional
    public ImmOrgDTO update(Long id, UpdateImmOrgRequest req) {
        log.info(">>> update() orgId={}", id);
        User caller = currentUser();
        requireOwner(caller, id);
        ImmOrg org = immOrgRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Org not found: " + id));
        if (req.name() != null && !req.name().isBlank()) {
            rejectHtml("name", req.name());
            org.setName(req.name().trim());
        }
        if (req.contactName()  != null) org.setContactName(req.contactName());
        if (req.contactEmail() != null) org.setContactEmail(req.contactEmail());
        if (req.address()      != null) org.setAddress(req.address());
        if (req.city()         != null) org.setCity(req.city());
        if (req.stateCode()    != null) org.setStateCode(req.stateCode());
        if (req.zipCode()      != null) org.setZipCode(req.zipCode());
        if (req.einNumber()    != null) org.setEinNumber(req.einNumber());
        if (req.website()      != null) org.setWebsite(req.website());
        ImmOrg saved = immOrgRepo.save(org);
        log.info("<<< update() orgId={}", saved.getId());
        return toDTO(saved);
    }

    private ImmOrgDTO toDTO(ImmOrg o, Long myMemberId) {
        return new ImmOrgDTO(o.getId(), o.getName(), o.getOrgType().name(),
                o.getOwnerUserId(), o.getCreatedAt().toString(), myMemberId,
                o.getContactName(), o.getContactEmail(),
                o.getAddress(), o.getCity(), o.getStateCode(), o.getZipCode(),
                o.getEinNumber(), o.getWebsite());
    }

    private ImmOrgMemberDTO toMemberDTO(ImmOrgMember m) {
        return new ImmOrgMemberDTO(m.getId(), m.getImmOrgId(), m.getUserId(),
                m.getEmail(), m.getRole().name(), m.getStatus().name(), null);
    }

    private static void rejectHtml(String fieldName, String value) {
        if (value != null && (value.contains("<") || value.contains(">"))) {
            throw new RuntimeException(fieldName + " must not contain HTML characters");
        }
    }
}
