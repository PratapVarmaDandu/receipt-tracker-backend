package com.receipttracker.immigration.model;

import com.receipttracker.model.User;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * ReBAC permission grant.
 * Exactly one of subjectUser or subjectOrg must be non-null.
 * Revocation: set revokedAt to the current time; never delete rows.
 */
@Entity
@Table(name = "imm_grants")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Grant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "case_id", nullable = false)
    private ImmigrationCase immigrationCase;

    // user-level grant (null when org-level)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subject_user_id")
    private User subjectUser;

    // org-level grant — loose ref to imm_orgs.id, no FK per cross-feature FK rule
    @Column(name = "imm_org_id")
    private Long subjectImmOrgId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "relationship", nullable = false, length = 50)
    private CaseRelationship relationship;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "scope", nullable = false, length = 50)
    private GrantScope scope;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "granted_by_id", nullable = false)
    private User grantedBy;

    @Column(name = "granted_at", nullable = false)
    private LocalDateTime grantedAt;

    // null = active; non-null = revoked at this time
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @PrePersist
    protected void onCreate() {
        grantedAt = LocalDateTime.now();
    }

    public boolean isActive() {
        return revokedAt == null;
    }
}
