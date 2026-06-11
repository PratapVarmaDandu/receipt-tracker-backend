package com.receipttracker.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "org_memberships",
       uniqueConstraints = @UniqueConstraint(columnNames = {"org_id", "invite_email"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrgMembership {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "org_id", nullable = false)
    private Organization org;

    /** Null until the invitee accepts and logs in. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "invite_email", nullable = false)
    private String inviteEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrgRole role = OrgRole.STAFF;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemberStatus status = MemberStatus.PENDING;

    @Column(name = "invite_token", unique = true)
    private String inviteToken;

    @Column(name = "invited_at")
    private LocalDateTime invitedAt;

    @Column(name = "joined_at")
    private LocalDateTime joinedAt;

    @PrePersist
    protected void onCreate() {
        inviteToken = UUID.randomUUID().toString();
        invitedAt   = LocalDateTime.now();
    }

    public enum OrgRole      { OWNER, ADMIN, STAFF, VIEWER }
    public enum MemberStatus { PENDING, ACTIVE, REVOKED }
}
