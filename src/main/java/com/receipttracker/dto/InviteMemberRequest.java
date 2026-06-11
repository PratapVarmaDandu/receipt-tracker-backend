package com.receipttracker.dto;

import lombok.Data;

@Data
public class InviteMemberRequest {
    private String email;
    private String role; // ADMIN | STAFF | VIEWER
}
