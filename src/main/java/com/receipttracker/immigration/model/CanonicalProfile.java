package com.receipttracker.immigration.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Beneficiary's canonical personal record.
 * One per beneficiary; created lazily on first save.
 * passportNumberEnc is AES-256-GCM encrypted via EncryptionService.
 * JSON sub-fields (address, education, employment, dependents, priorVisas)
 * are stored as TEXT for H2 + MySQL compatibility — serialized by CanonicalProfileService.
 */
@Entity
@Table(name = "imm_canonical_profiles")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CanonicalProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "beneficiary_id", nullable = false, unique = true)
    private Beneficiary beneficiary;

    // ── Bio ──────────────────────────────────────────────────────────────────
    @Column(name = "legal_first_name")   private String legalFirstName;
    @Column(name = "legal_last_name")    private String legalLastName;
    @Column(name = "middle_name")        private String middleName;
    @Column(name = "date_of_birth")      private LocalDate dateOfBirth;
    @Column(name = "country_of_birth")   private String countryOfBirth;
    @Column(name = "citizenship_country") private String citizenshipCountry;
    @Column(name = "gender")             private String gender;

    // ── Travel document ───────────────────────────────────────────────────────
    // TODO: verify field against official form instruction (passport number format varies by country)
    @Column(name = "passport_number_enc", length = 1024)
    private String passportNumberEnc; // AES-256-GCM encrypted

    @Column(name = "passport_country")      private String passportCountry;
    @Column(name = "passport_issue_date")   private LocalDate passportIssueDate;
    @Column(name = "passport_expiry_date")  private LocalDate passportExpiryDate;

    // ── US entry & current status ──────────────────────────────────────────────
    @Column(name = "port_of_entry")    private String portOfEntry;
    @Column(name = "entry_date")       private LocalDate entryDate;

    // TODO: verify field against official form instruction (I-94 admission number is 11 digits)
    @Column(name = "i94_number")       private String i94Number;

    // Free text — the visa class printed on the visa stamp (e.g. H-1B, F-1, L-1)
    @Column(name = "current_visa_type")   private String currentVisaType;
    @Column(name = "current_visa_expiry") private LocalDate currentVisaExpiry;

    // ── Contact ───────────────────────────────────────────────────────────────
    @Column(name = "phone") private String phone;

    // ── JSON sub-fields (TEXT, H2+MySQL safe) ─────────────────────────────────
    // { line1, line2, city, state, zip, country }
    @Column(name = "current_address_json", columnDefinition = "TEXT")
    private String currentAddressJson;

    // [ { institution, degree, field, startYear, endYear } ]
    @Column(name = "education_json", columnDefinition = "TEXT")
    private String educationJson;

    // [ { employer, title, startDate, endDate, country } ]
    @Column(name = "employment_json", columnDefinition = "TEXT")
    private String employmentJson;

    // [ { name, relationship, dateOfBirth, citizenshipCountry } ]
    @Column(name = "dependents_json", columnDefinition = "TEXT")
    private String dependentsJson;

    // [ { visaType, country, issueDate, expiryDate } ]
    @Column(name = "prior_visas_json", columnDefinition = "TEXT")
    private String priorVisasJson;

    // ── Passports (list — replaces single passport fields for display; single fields kept for FormMappingService) ──
    // Each item: { id, numberEnc (AES-256-GCM), country, issueDate, expiryDate, notes, documentIds[] }
    @Column(name = "passports_json", columnDefinition = "TEXT")
    private String passportsJson;

    // ── Travel entries (list — replaces single I-94 fields for display) ──────
    // Each item: { id, portOfEntry, i94Number, entryDate, admittedUntil, visaClass, notes, documentIds[] }
    @Column(name = "travel_entries_json", columnDefinition = "TEXT")
    private String travelEntriesJson;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at")                   private LocalDateTime updatedAt;

    @PrePersist  protected void onCreate() { createdAt = updatedAt = LocalDateTime.now(); }
    @PreUpdate   protected void onUpdate()  { updatedAt = LocalDateTime.now(); }
}
