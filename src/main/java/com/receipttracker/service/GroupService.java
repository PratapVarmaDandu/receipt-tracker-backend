package com.receipttracker.service;

import com.receipttracker.dto.CreateGroupRequest;
import com.receipttracker.dto.GroupDTO;
import com.receipttracker.dto.GroupMemberDTO;
import com.receipttracker.model.ExpenseGroup;
import com.receipttracker.model.GroupMember;
import com.receipttracker.model.GroupMember.GroupRole;
import com.receipttracker.model.Receipt;
import com.receipttracker.model.User;
import com.receipttracker.repository.ExpenseGroupRepository;
import com.receipttracker.repository.GroupMemberRepository;
import com.receipttracker.repository.ReceiptRepository;
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
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class GroupService {

    private static final Logger log = LoggerFactory.getLogger(GroupService.class);

    @Autowired private ExpenseGroupRepository groupRepo;
    @Autowired private GroupMemberRepository memberRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private ReceiptRepository receiptRepo;

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        OAuth2User principal = (OAuth2User) auth.getPrincipal();
        String googleId = principal.getAttribute("sub");
        return userRepo.findByGoogleId(googleId)
                .orElseThrow(() -> new RuntimeException("Authenticated user not found"));
    }

    @Transactional
    public GroupDTO createGroup(CreateGroupRequest req) {
        if (req.getName() == null || req.getName().isBlank()) {
            throw new RuntimeException("Group name is required");
        }
        User owner = currentUser();
        ExpenseGroup group = new ExpenseGroup();
        group.setName(req.getName().trim());
        group.setOwner(owner);
        group = groupRepo.save(group);

        GroupMember ownerMember = new GroupMember();
        ownerMember.setGroup(group);
        ownerMember.setUser(owner);
        ownerMember.setRole(GroupRole.OWNER);
        memberRepo.save(ownerMember);

        log.info("Created group id={} name={} owner={}", group.getId(), group.getName(), owner.getEmail());
        return toDTO(group, owner);
    }

    @Transactional(readOnly = true)
    public List<GroupDTO> getMyGroups() {
        User caller = currentUser();
        return memberRepo.findGroupsByUser(caller).stream()
                .map(g -> toDTO(g, caller))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public GroupDTO getGroupByInviteToken(String token) {
        ExpenseGroup group = groupRepo.findByInviteToken(token)
                .orElseThrow(() -> new RuntimeException("Group not found"));
        return toDTO(group, null);
    }

    @Transactional
    public GroupDTO joinGroup(String token) {
        User caller = currentUser();
        ExpenseGroup group = groupRepo.findByInviteToken(token)
                .orElseThrow(() -> new RuntimeException("Invalid or expired group invite link"));

        if (memberRepo.existsByGroupAndUser(group, caller)) {
            return toDTO(group, caller);
        }

        GroupMember member = new GroupMember();
        member.setGroup(group);
        member.setUser(caller);
        member.setRole(GroupRole.MEMBER);
        memberRepo.save(member);

        log.info("User {} joined group id={}", caller.getEmail(), group.getId());
        return toDTO(group, caller);
    }

    @Transactional(readOnly = true)
    public GroupDTO getGroupById(Long id) {
        User caller = currentUser();
        ExpenseGroup group = groupRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Group not found"));
        if (!memberRepo.existsByGroupAndUser(group, caller)) {
            throw new RuntimeException("You are not a member of this group");
        }
        return toDTOWithMembers(group, caller);
    }

    @Transactional
    public void deleteGroup(Long id) {
        User caller = currentUser();
        ExpenseGroup group = groupRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Group not found"));
        GroupMember callerMember = memberRepo.findByGroupAndUser(group, caller)
                .orElseThrow(() -> new RuntimeException("You are not a member of this group"));
        if (callerMember.getRole() != GroupRole.OWNER) {
            throw new RuntimeException("Only the group owner can delete this group");
        }

        // Unassign all receipts from this group
        List<Receipt> receipts = receiptRepo.findByGroup(group);
        receipts.forEach(r -> r.setGroup(null));
        receiptRepo.saveAll(receipts);

        // Remove all members then the group
        memberRepo.deleteAll(memberRepo.findByGroup(group));
        groupRepo.delete(group);
        log.info("Deleted group id={} by owner={}", id, caller.getEmail());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getGroupReceipts(Long groupId) {
        User caller = currentUser();
        ExpenseGroup group = groupRepo.findById(groupId)
                .orElseThrow(() -> new RuntimeException("Group not found"));
        if (!memberRepo.existsByGroupAndUser(group, caller)) {
            throw new RuntimeException("You are not a member of this group");
        }
        return receiptRepo.findByGroup(group).stream()
                .map(r -> Map.<String, Object>of(
                        "id", r.getId(),
                        "storeName", r.getStoreName() != null ? r.getStoreName() : "",
                        "total", r.getTotal() != null ? r.getTotal() : java.math.BigDecimal.ZERO,
                        "purchaseDateTime", r.getPurchaseDateTime() != null ? r.getPurchaseDateTime().toString() : "",
                        "ownerEmail", r.getUser() != null ? r.getUser().getEmail() : ""
                ))
                .collect(Collectors.toList());
    }

    private GroupDTO toDTO(ExpenseGroup group, User currentUser) {
        GroupDTO dto = new GroupDTO();
        dto.setId(group.getId());
        dto.setName(group.getName());
        dto.setOwnerName(group.getOwner().getName());
        dto.setInviteToken(group.getInviteToken());
        dto.setCreatedAt(group.getCreatedAt());
        List<GroupMember> members = memberRepo.findByGroup(group);
        dto.setMemberCount(members.size());
        if (currentUser != null) {
            members.stream()
                    .filter(m -> m.getUser().getId().equals(currentUser.getId()))
                    .findFirst()
                    .ifPresent(m -> dto.setCurrentUserRole(m.getRole()));
        }
        return dto;
    }

    private GroupDTO toDTOWithMembers(ExpenseGroup group, User currentUser) {
        GroupDTO dto = toDTO(group, currentUser);
        List<GroupMember> members = memberRepo.findByGroup(group);
        dto.setMembers(members.stream().map(m -> {
            GroupMemberDTO mdto = new GroupMemberDTO();
            mdto.setId(m.getId());
            mdto.setName(m.getUser().getName());
            mdto.setEmail(m.getUser().getEmail());
            mdto.setPicture(m.getUser().getPicture());
            mdto.setRole(m.getRole());
            mdto.setJoinedAt(m.getJoinedAt());
            return mdto;
        }).collect(Collectors.toList()));
        return dto;
    }
}
