package com.receipttracker.immigration.service;

import com.receipttracker.immigration.dto.ActivityFeedItemDTO;
import com.receipttracker.immigration.model.*;
import com.receipttracker.immigration.repository.GrantRepository;
import com.receipttracker.immigration.repository.ImmAuditEventRepository;
import com.receipttracker.immigration.repository.ImmigrationCaseRepository;
import com.receipttracker.immigration.model.ImmOrgMember;
import com.receipttracker.immigration.model.ImmOrgMemberStatus;
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
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ActivityFeedService {

    private static final Logger log = LoggerFactory.getLogger(ActivityFeedService.class);

    @Autowired private ImmAuditEventRepository auditRepo;
    @Autowired private GrantRepository grantRepo;
    @Autowired private ImmigrationCaseRepository caseRepo;
    @Autowired private ImmOrgMemberRepository immOrgMemberRepo;
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
    public List<ActivityFeedItemDTO> getFeed(Long caseId) {
        log.info(">>> getFeed() caseId={}", caseId);
        User user = currentUser();
        permissionService.requireAccess(user, caseId, GrantScope.READ_CASE);

        ImmigrationCase c = caseRepo.findById(caseId)
                .orElseThrow(() -> new RuntimeException("Case not found: " + caseId));

        List<FeedVisibility> allowed = resolveVisibilities(user, c);

        return auditRepo.findVisibleEvents(c, allowed)
                .stream().map(e -> new ActivityFeedItemDTO(
                        e.getId(),
                        e.getAction(),
                        e.getActor() != null ? e.getActor().getName() : "System",
                        e.getDetail(),
                        e.getCreatedAt()
                )).toList();
    }

    private List<FeedVisibility> resolveVisibilities(User user, ImmigrationCase c) {
        // Determine the user's relationship to the case via their active Grants
        List<Long> orgIds = immOrgMemberRepo.findByUserIdAndStatus(user.getId(), ImmOrgMemberStatus.ACTIVE)
                .stream().map(ImmOrgMember::getImmOrgId).toList();
        List<Long> orgIdsWithFallback = orgIds.isEmpty() ? List.of(-1L) : orgIds;

        Set<String> relationships = grantRepo.findByImmigrationCaseAndRevokedAtIsNull(c)
                .stream()
                .filter(g -> g.getSubjectUser() != null && g.getSubjectUser().getId().equals(user.getId())
                        || g.getSubjectImmOrgId() != null && orgIdsWithFallback.contains(g.getSubjectImmOrgId()))
                .map(g -> g.getRelationship().name())
                .collect(Collectors.toSet());

        // Everyone with any grant sees ALL events; attorney also sees ATTORNEY_ONLY; beneficiary sees BENEFICIARY_ONLY
        List<FeedVisibility> visible = new java.util.ArrayList<>();
        visible.add(FeedVisibility.ALL);
        if (relationships.contains(CaseRelationship.ATTORNEY.name()) ||
            relationships.contains(CaseRelationship.PARALEGAL.name())) {
            visible.add(FeedVisibility.ATTORNEY_ONLY);
        }
        if (relationships.contains(CaseRelationship.BENEFICIARY.name())) {
            visible.add(FeedVisibility.BENEFICIARY_ONLY);
        }
        return visible;
    }
}
