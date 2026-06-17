package com.receipttracker.immigration.repository;

import com.receipttracker.immigration.model.ImmOrgMember;
import com.receipttracker.immigration.model.ImmOrgMemberStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ImmOrgMemberRepository extends JpaRepository<ImmOrgMember, Long> {

    List<ImmOrgMember> findByImmOrgId(Long immOrgId);

    List<ImmOrgMember> findByUserIdAndStatus(Long userId, ImmOrgMemberStatus status);

    Optional<ImmOrgMember> findByInviteToken(String token);

    Optional<ImmOrgMember> findByEmailAndImmOrgId(String email, Long immOrgId);
}
