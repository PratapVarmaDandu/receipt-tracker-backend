package com.receipttracker.dto;

import com.receipttracker.model.GroupMember.GroupRole;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class GroupMemberDTO {
    private Long id;
    private String name;
    private String email;
    private String picture;
    private GroupRole role;
    private LocalDateTime joinedAt;
}
