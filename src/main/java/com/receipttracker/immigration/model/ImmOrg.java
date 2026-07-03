package com.receipttracker.immigration.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "imm_orgs")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImmOrg {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "org_type", nullable = false, length = 50)
    private ImmOrgType orgType;

    // loose ref to users.id — no FK per cross-feature FK rule
    @Column(name = "owner_user_id")
    private Long ownerUserId;

    // Employer profile fields (nullable for law firms)
    @Column(name = "contact_name")
    private String contactName;

    @Column(name = "contact_email")
    private String contactEmail;

    @Column(name = "address")
    private String address;

    @Column(name = "city")
    private String city;

    @Column(name = "state_code", length = 10)
    private String stateCode;

    @Column(name = "zip_code", length = 20)
    private String zipCode;

    @Column(name = "country")
    private String country;

    @Column(name = "ein_number", length = 20)
    private String einNumber;

    @Column(name = "website")
    private String website;

    // I-129 Part 1 Item 6 — single Yes/No; the granular ACWIA cap-exempt category
    // set lives in the generic answer store (H-1B Data Collection Supplement)
    @Column(name = "nonprofit_or_gov_research")
    private Boolean nonprofitOrGovResearch;

    // I-129 Part 5 Items 12-17 — basic employer characteristics used across every H-1B filing
    @Column(name = "business_type")
    private String businessType;

    @Column(name = "year_established")
    private Integer yearEstablished;

    @Column(name = "employee_count")
    private Integer employeeCount;

    @Column(name = "gross_annual_income", precision = 15, scale = 2)
    private BigDecimal grossAnnualIncome;

    @Column(name = "net_annual_income", precision = 15, scale = 2)
    private BigDecimal netAnnualIncome;

    @Column(name = "small_employer_flag")
    private Boolean smallEmployerFlag;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
