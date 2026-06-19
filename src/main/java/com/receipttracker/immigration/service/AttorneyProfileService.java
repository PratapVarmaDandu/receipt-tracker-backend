package com.receipttracker.immigration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.receipttracker.immigration.dto.AttorneyProfileDTO;
import com.receipttracker.immigration.dto.UpdateAttorneyProfileRequest;
import com.receipttracker.immigration.model.AttorneyProfile;
import com.receipttracker.immigration.model.ImmOrgMember;
import com.receipttracker.immigration.model.ImmOrgMemberRole;
import com.receipttracker.immigration.model.ImmOrgMemberStatus;
import com.receipttracker.immigration.repository.AttorneyProfileRepository;
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

import java.util.List;

@Service
public class AttorneyProfileService {

    private static final Logger log = LoggerFactory.getLogger(AttorneyProfileService.class);

    @Autowired private AttorneyProfileRepository profileRepo;
    @Autowired private ImmOrgMemberRepository memberRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private ObjectMapper objectMapper;

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        OAuth2User principal = (OAuth2User) auth.getPrincipal();
        String googleId = principal.getAttribute("sub");
        return userRepo.findByGoogleId(googleId)
                .orElseThrow(() -> new RuntimeException("Authenticated user not found"));
    }

    /**
     * Returns the attorney profile for the current user within the given org.
     * Caller must be ATTORNEY or OWNER of that org.
     * Returns an empty DTO (id=null) if no profile has been saved yet.
     */
    @Transactional(readOnly = true)
    public AttorneyProfileDTO get(Long orgId) {
        log.info(">>> getAttorneyProfile() orgId={}", orgId);
        User caller = currentUser();
        ImmOrgMember member = requireAttorneyOrOwner(caller, orgId);
        return profileRepo.findByImmOrgMemberId(member.getId())
                .map(this::toDTO)
                .orElse(emptyDTO(member.getId()));
    }

    /**
     * Creates or updates the attorney profile for the current user within the given org.
     * Caller must be ATTORNEY or OWNER of that org.
     */
    @Transactional
    public AttorneyProfileDTO update(Long orgId, UpdateAttorneyProfileRequest req) {
        log.info(">>> updateAttorneyProfile() orgId={}", orgId);
        User caller = currentUser();
        ImmOrgMember member = requireAttorneyOrOwner(caller, orgId);

        AttorneyProfile profile = profileRepo.findByImmOrgMemberId(member.getId())
                .orElseGet(() -> {
                    AttorneyProfile p = new AttorneyProfile();
                    p.setImmOrgMemberId(member.getId());
                    return p;
                });

        if (req.barNumbers() != null) profile.setBarNumbersJson(toJson(req.barNumbers()));
        if (req.bio()        != null) profile.setBio(req.bio());

        AttorneyProfile saved = profileRepo.save(profile);
        log.info("<<< updateAttorneyProfile() profileId={}", saved.getId());
        return toDTO(saved);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ImmOrgMember requireAttorneyOrOwner(User user, Long orgId) {
        List<ImmOrgMember> active = memberRepo.findByUserIdAndStatus(user.getId(), ImmOrgMemberStatus.ACTIVE);
        return active.stream()
                .filter(m -> m.getImmOrgId().equals(orgId)
                        && (m.getRole() == ImmOrgMemberRole.ATTORNEY
                         || m.getRole() == ImmOrgMemberRole.OWNER))
                .findFirst()
                .orElseThrow(() -> new RuntimeException(
                        "Access denied: ATTORNEY or OWNER role required in org " + orgId));
    }

    private AttorneyProfileDTO toDTO(AttorneyProfile p) {
        return new AttorneyProfileDTO(
                p.getId(),
                p.getImmOrgMemberId(),
                parseJson(p.getBarNumbersJson()),
                p.getBio(),
                p.getSignatureImageKey(),
                p.getCreatedAt(),
                p.getUpdatedAt()
        );
    }

    private AttorneyProfileDTO emptyDTO(Long memberId) {
        return new AttorneyProfileDTO(null, memberId, List.of(), null, null, null, null);
    }

    private String toJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { throw new RuntimeException("JSON serialization failed", e); }
    }

    private Object parseJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return objectMapper.readValue(json, Object.class); }
        catch (Exception e) { log.warn("!!! Failed to parse bar numbers JSON: {}", e.getMessage()); return List.of(); }
    }
}
