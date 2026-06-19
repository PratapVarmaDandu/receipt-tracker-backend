package com.receipttracker.immigration.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Dependent family member linked to a beneficiary.
 * passport_number_enc and alien_number_enc are AES-256-GCM encrypted via EncryptionService.
 * Supersedes the dependents_json TEXT column on CanonicalProfile for new writes;
 * that column is retained for backward compat during migration.
 */
@Entity
@Table(name = "imm_dependents")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImmDependent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "beneficiary_id", nullable = false)
    private Beneficiary beneficiary;

    @Column(name = "first_name",  nullable = false, length = 200)
    private String firstName;

    @Column(name = "last_name",   nullable = false, length = 200)
    private String lastName;

    @Column(name = "middle_name", length = 200)
    private String middleName;

    // e.g. SPOUSE, CHILD, PARENT
    @Column(name = "relationship", nullable = false, length = 50)
    private String relationship;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "citizenship_country", length = 100)
    private String citizenshipCountry;

    // TODO: verify field against official form instruction (passport number format varies by country)
    @Column(name = "passport_number_enc", length = 1024)
    private String passportNumberEnc; // AES-256-GCM encrypted

    // TODO: verify field against official form instruction (A-Number is 9 digits, A-XXXXXXXXX format)
    @Column(name = "alien_number_enc", length = 1024)
    private String alienNumberEnc; // AES-256-GCM encrypted

    @Column(name = "has_own_ead", nullable = false)
    private boolean hasOwnEad = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist  protected void onCreate() { createdAt = updatedAt = LocalDateTime.now(); }
    @PreUpdate   protected void onUpdate()  { updatedAt = LocalDateTime.now(); }
}
