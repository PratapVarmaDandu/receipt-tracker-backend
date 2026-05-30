package com.receipttracker.dto;

import com.receipttracker.model.GroupMember.GroupRole;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class GroupDTO {
    private Long id;
    private String name;
    private String ownerName;
    private String inviteToken;
    private LocalDateTime createdAt;
    private int memberCount;
    private GroupRole currentUserRole;
    private List<GroupMemberDTO> members;
}
