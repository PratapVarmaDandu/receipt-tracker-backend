package com.receipttracker.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OrgMemberDTO {
    private Long id;
    private String inviteEmail;
    private String role;
    private String status;
    private String userName;
    private String userPicture;
    private LocalDateTime invitedAt;
    private LocalDateTime joinedAt;
}
