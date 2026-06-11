package com.receipttracker.repository;

import com.receipttracker.model.OrgMembership;
import com.receipttracker.model.OrgMembership.MemberStatus;
import com.receipttracker.model.Organization;
import com.receipttracker.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrgMembershipRepository extends JpaRepository<OrgMembership, Long> {
    List<OrgMembership> findByOrgOrderByInvitedAtDesc(Organization org);
    List<OrgMembership> findByUserAndStatusNot(User user, MemberStatus status);
    Optional<OrgMembership> findByInviteToken(String token);
    boolean existsByOrgAndInviteEmailAndStatusNot(Organization org, String email, MemberStatus status);
    Optional<OrgMembership> findByOrgAndInviteEmail(Organization org, String inviteEmail);
    Optional<OrgMembership> findByOrgAndUser(Organization org, User user);
}
