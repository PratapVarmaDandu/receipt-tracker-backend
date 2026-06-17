package com.receipttracker.immigration.dto;

public record ImmOrgMemberDTO(
        Long id, Long immOrgId, Long userId,
        String email, String role, String status,
        String inviteToken
) {}
