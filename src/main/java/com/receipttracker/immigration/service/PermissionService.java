package com.receipttracker.immigration.service;

import com.receipttracker.immigration.model.ImmOrgMember;
import com.receipttracker.immigration.model.ImmOrgMemberStatus;
import com.receipttracker.immigration.model.GrantScope;
import com.receipttracker.immigration.repository.GrantRepository;
import com.receipttracker.immigration.repository.ImmOrgMemberRepository;
import com.receipttracker.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ReBAC gate for all immigration case data.
 *
 * MUST be called as the first line of every CaseService / FormService / etc. method
 * that touches case data. No data access path may bypass this check.
 *
 * Access is granted when an active (non-revoked) Grant exists linking:
 *   (the user directly) OR (any ImmOrg the user is an active member of)
 *   to the requested Case with the requested scope.
 */
@Service
public class PermissionService {

    private static final Logger log = LoggerFactory.getLogger(PermissionService.class);

    @Autowired private GrantRepository grantRepo;
    @Autowired private ImmOrgMemberRepository immOrgMemberRepo;

    @Transactional(readOnly = true)
    public boolean canAccess(User user, Long caseId, GrantScope scope) {
        List<Long> userOrgIds = activeImmOrgIds(user);
        boolean allowed = grantRepo.existsActiveGrant(caseId, scope, user.getId(), userOrgIds);
        if (!allowed) {
            log.warn("!!! ACCESS DENIED user={} case={} scope={}", user.getId(), caseId, scope);
        }
        return allowed;
    }

    @Transactional(readOnly = true)
    public void requireAccess(User user, Long caseId, GrantScope scope) {
        if (!canAccess(user, caseId, scope)) {
            throw new RuntimeException("Access denied: insufficient permissions");
        }
    }

    public List<Long> activeImmOrgIds(User user) {
        List<Long> ids = immOrgMemberRepo.findByUserIdAndStatus(user.getId(), ImmOrgMemberStatus.ACTIVE)
                .stream().map(ImmOrgMember::getImmOrgId).toList();
        // JPA IN clause cannot be empty — use -1 sentinel when user has no org memberships
        return ids.isEmpty() ? List.of(-1L) : ids;
    }
}
