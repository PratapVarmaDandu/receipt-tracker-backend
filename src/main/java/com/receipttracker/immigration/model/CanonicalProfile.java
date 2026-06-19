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
    // Legacy plain column — kept for backward compat; prefer i94_number_enc for new writes
    @Column(name = "i94_number")       private String i94Number;

    // AES-256-GCM encrypted I-94 number — supersedes i94_number plain column
    @Column(name = "i94_number_enc", length = 1024)
    private String i94NumberEnc;

    // TODO: verify field against official form instruction (A-Number is 9 digits, A-XXXXXXXXX format)
    @Column(name = "alien_number_enc", length = 1024)
    private String alienNumberEnc; // AES-256-GCM encrypted

    // TODO: verify field against official form instruction (SSN format NNN-NN-NNNN)
    @Column(name = "ssn_enc", length = 1024)
    private String ssnEnc; // AES-256-GCM encrypted — required for I-485, I-765

    // ── EAD (Employment Authorization Document) ────────────────────────────────
    @Column(name = "ead_card_number_enc", length = 1024)
    private String eadCardNumberEnc; // AES-256-GCM encrypted

    // TODO: verify field against official form instruction (EAD category codes e.g. (c)(3)(C), (a)(17))
    @Column(name = "ead_category", length = 50)
    private String eadCategory;

    @Column(name = "ead_expiry_date")
    private LocalDate eadExpiryDate;

    @Column(name = "ead_case_number", length = 100)
    private String eadCaseNumber;

    // Free text — the visa class printed on the visa stamp (e.g. H-1B, F-1, L-1)
    @Column(name = "current_visa_type")   private String currentVisaType;
    @Column(name = "current_visa_expiry") private LocalDate currentVisaExpiry;

    // ── Contact ───────────────────────────────────────────────────────────────
    @Column(name = "phone") private String phone;

    // ── Notification preferences ──────────────────────────────────────────────
    @Column(name = "notification_email_enabled", nullable = false)
    private boolean notificationEmailEnabled = true;

    @Column(name = "notification_sms_enabled", nullable = false)
    private boolean notificationSmsEnabled = false;

    @Column(name = "notification_phone", length = 30)
    private String notificationPhone;

    // ── USCIS / profile preferences ───────────────────────────────────────────
    @Column(name = "uscis_online_account_number", length = 50)
    private String uscisOnlineAccountNumber;

    @Column(name = "preferred_language", length = 10)
    private String preferredLanguage = "en";

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
