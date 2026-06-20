package com.receipttracker.immigration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.receipttracker.immigration.dto.CanonicalProfileDTO;
import com.receipttracker.immigration.dto.UpdateProfileRequest;
import com.receipttracker.immigration.model.Beneficiary;
import com.receipttracker.immigration.model.CanonicalProfile;
import com.receipttracker.immigration.model.GrantScope;
import com.receipttracker.immigration.model.ImmigrationCase;
import com.receipttracker.immigration.repository.BeneficiaryRepository;
import com.receipttracker.immigration.repository.CanonicalProfileRepository;
import com.receipttracker.immigration.repository.ImmigrationCaseRepository;
import com.receipttracker.model.Document;
import com.receipttracker.model.User;
import com.receipttracker.repository.DocumentRepository;
import com.receipttracker.repository.UserRepository;
import com.receipttracker.service.DocumentService;
import com.receipttracker.service.EncryptionService;
import org.springframework.core.io.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class CanonicalProfileService {

    private static final Logger log = LoggerFactory.getLogger(CanonicalProfileService.class);

    @Autowired private CanonicalProfileRepository profileRepo;
    @Autowired private BeneficiaryRepository beneficiaryRepo;
    @Autowired private ImmigrationCaseRepository caseRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private EncryptionService encryptionService;
    @Autowired private PermissionService permissionService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private DocumentRepository documentRepo;
    @Autowired private DocumentService documentService;
    @Autowired private AuditService auditService;

    // ── User resolution ──────────────────────────────────────────────────────

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        OAuth2User principal = (OAuth2User) auth.getPrincipal();
        String googleId = principal.getAttribute("sub");
        return userRepo.findByGoogleId(googleId)
                .orElseThrow(() -> new RuntimeException("Authenticated user not found"));
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Returns the canonical profile for the current user, creating an empty one if none exists.
     * No PermissionService check — a beneficiary always has access to their own profile.
     */
    @Transactional
    public CanonicalProfileDTO getOrCreateForCurrentUser() {
        log.info(">>> getOrCreateForCurrentUser()");
        User user = currentUser();
        Beneficiary beneficiary = beneficiaryRepo.findByUser(user)
                .orElseThrow(() -> new RuntimeException("Register as beneficiary first"));

        CanonicalProfile profile = profileRepo.findByBeneficiary(beneficiary)
                .orElseGet(() -> {
                    log.info("Creating empty CanonicalProfile for beneficiary {}", beneficiary.getId());
                    CanonicalProfile p = new CanonicalProfile();
                    p.setBeneficiary(beneficiary);
                    return profileRepo.save(p);
                });
        return toDTO(profile);
    }

    /**
     * View a beneficiary's profile when accessing via a case.
     * Requires READ_CASE grant — called by attorneys / HR admins.
     */
    @Transactional(readOnly = true)
    public CanonicalProfileDTO getForCase(Long caseId) {
        log.info(">>> getForCase() caseId={}", caseId);
        User user = currentUser();
        permissionService.requireAccess(user, caseId, GrantScope.READ_CASE);
        ImmigrationCase c = caseRepo.findById(caseId)
                .orElseThrow(() -> new RuntimeException("Case not found: " + caseId));
        Beneficiary beneficiary = c.getBeneficiary();
        CanonicalProfile profile = profileRepo.findByBeneficiary(beneficiary)
                .orElseThrow(() -> new RuntimeException("Beneficiary has not set up their profile yet"));
        return toDTO(profile);
    }

    /**
     * Proxy-download a vault document attached to a beneficiary's profile section.
     * Requires READ_CASE grant (attorney / HR admin). The document must belong to the beneficiary.
     */
    @Transactional(readOnly = true)
    public Resource downloadProfileDocument(Long caseId, Long docId) throws java.io.IOException {
        log.info(">>> downloadProfileDocument() caseId={} docId={}", caseId, docId);
        User caller = currentUser();
        permissionService.requireAccess(caller, caseId, GrantScope.READ_CASE);
        ImmigrationCase c = caseRepo.findById(caseId)
                .orElseThrow(() -> new RuntimeException("Case not found: " + caseId));
        Long beneficiaryUserId = c.getBeneficiary().getUser().getId();
        Document doc = documentRepo.findById(docId)
                .orElseThrow(() -> new RuntimeException("Document not found: " + docId));
        if (!doc.getUser().getId().equals(beneficiaryUserId)) {
            throw new RuntimeException("Access denied: document does not belong to this case's beneficiary");
        }
        return documentService.downloadByPath(beneficiaryUserId, doc.getStoredFileName());
    }

    /**
     * Update a beneficiary's profile on their behalf (called by ProfileDataRequestService
     * after the intake token has been validated). Skips auth check intentionally.
     */
    @Transactional
    void updateForBeneficiary(Beneficiary beneficiary, UpdateProfileRequest req) {
        log.info(">>> updateForBeneficiary() beneficiaryId={}", beneficiary.getId());
        CanonicalProfile profile = profileRepo.findByBeneficiary(beneficiary)
                .orElseGet(() -> {
                    CanonicalProfile p = new CanonicalProfile();
                    p.setBeneficiary(beneficiary);
                    return p;
                });
        applyUpdate(profile, req);
        profileRepo.save(profile);
        log.info("<<< updateForBeneficiary() done");
    }

    /**
     * Update the current user's canonical profile.
     * No PermissionService check — validates that caller IS the beneficiary.
     */
    @Transactional
    public CanonicalProfileDTO updateForCurrentUser(UpdateProfileRequest req) {
        log.info(">>> updateForCurrentUser()");
        User user = currentUser();
        Beneficiary beneficiary = beneficiaryRepo.findByUser(user)
                .orElseThrow(() -> new RuntimeException("Register as beneficiary first"));

        CanonicalProfile profile = profileRepo.findByBeneficiary(beneficiary)
                .orElseGet(() -> {
                    CanonicalProfile p = new CanonicalProfile();
                    p.setBeneficiary(beneficiary);
                    return p;
                });

        // Capture scalar old-values for field-level audit before mutation
        Map<String, String> oldSnapshot = captureProfileSnapshot(profile);

        applyUpdate(profile, req);
        CanonicalProfile saved = profileRepo.save(profile);
        log.info("<<< updateForCurrentUser() profileId={}", saved.getId());

        // Emit one audit event per changed field (non-fatal, caseId=null — profile is cross-case)
        emitProfileFieldAudits(oldSnapshot, req, saved.getId(), user.getId());

        return toDTO(saved);
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    CanonicalProfileDTO toDTO(CanonicalProfile p) {
        return new CanonicalProfileDTO(
                p.getId(),
                p.getBeneficiary().getId(),
                p.getLegalFirstName(),
                p.getLegalLastName(),
                p.getMiddleName(),
                p.getDateOfBirth()       != null ? p.getDateOfBirth().toString()       : null,
                p.getCountryOfBirth(),
                p.getCitizenshipCountry(),
                p.getGender(),
                buildPassportsDto(p),
                buildTravelEntriesDto(p),
                p.getCurrentVisaType(),
                p.getCurrentVisaExpiry() != null ? p.getCurrentVisaExpiry().toString() : null,
                p.getPhone(),
                parseJson(p.getCurrentAddressJson()),
                parseJson(p.getEducationJson()),
                parseJson(p.getEmploymentJson()),
                parseJson(p.getDependentsJson()),
                parseJson(p.getPriorVisasJson()),
                p.getNotes(),
                // Sensitive presence flags — raw values never returned
                p.getSsnEnc()          != null && !p.getSsnEnc().isBlank(),
                p.getAlienNumberEnc()  != null && !p.getAlienNumberEnc().isBlank(),
                p.getI94NumberEnc()    != null && !p.getI94NumberEnc().isBlank(),
                p.getEadCardNumberEnc() != null && !p.getEadCardNumberEnc().isBlank(),
                // EAD (non-sensitive)
                p.getEadCategory(),
                p.getEadExpiryDate()   != null ? p.getEadExpiryDate().toString()   : null,
                p.getEadCaseNumber(),
                // Notification preferences
                p.isNotificationEmailEnabled(),
                p.isNotificationSmsEnabled(),
                p.getNotificationPhone(),
                // USCIS / profile preferences
                p.getUscisOnlineAccountNumber(),
                p.getPreferredLanguage() != null ? p.getPreferredLanguage() : "en",
                p.getCreatedAt(),
                p.getUpdatedAt()
        );
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private void applyUpdate(CanonicalProfile p, UpdateProfileRequest req) {
        // Bio
        if (req.legalFirstName()     != null) p.setLegalFirstName(req.legalFirstName());
        if (req.legalLastName()      != null) p.setLegalLastName(req.legalLastName());
        if (req.middleName()         != null) p.setMiddleName(req.middleName());
        if (req.dateOfBirth()        != null) p.setDateOfBirth(parseDate(req.dateOfBirth()));
        if (req.countryOfBirth()     != null) p.setCountryOfBirth(req.countryOfBirth());
        if (req.citizenshipCountry() != null) p.setCitizenshipCountry(req.citizenshipCountry());
        if (req.gender()             != null) p.setGender(req.gender());

        // Passports — encrypt numbers, persist array, sync current to single fields for FormMappingService
        if (req.passports() != null) {
            List<Map<String, Object>> encrypted = encryptPassportNumbers(req.passports());
            p.setPassportsJson(toJson(encrypted));
            syncCurrentPassportToSingleFields(p, encrypted);
        }

        // Travel entries — persist array, sync most-recent to single I-94 fields for FormMappingService
        if (req.travelEntries() != null) {
            p.setTravelEntriesJson(toJson(req.travelEntries()));
            syncMostRecentTravelEntry(p, req.travelEntries());
        }

        // Current visa status (standalone — separate from travel history)
        if (req.currentVisaType()   != null) p.setCurrentVisaType(req.currentVisaType());
        if (req.currentVisaExpiry() != null) p.setCurrentVisaExpiry(parseDate(req.currentVisaExpiry()));

        // Contact
        if (req.phone() != null) p.setPhone(req.phone());

        // Sensitive fields — encrypt before persist; audit hash only (never log plaintext)
        if (req.alienNumber()   != null && !req.alienNumber().isBlank())
            p.setAlienNumberEnc(encryptionService.encrypt(req.alienNumber()));
        if (req.ssn()           != null && !req.ssn().isBlank())
            p.setSsnEnc(encryptionService.encrypt(req.ssn()));
        if (req.i94Number()     != null && !req.i94Number().isBlank()) {
            p.setI94NumberEnc(encryptionService.encrypt(req.i94Number()));
            // Auto-migrate: also write legacy plain column so FormMappingService keeps working
            // until it is updated to read the encrypted column
            p.setI94Number(req.i94Number());
        }
        if (req.eadCardNumber() != null && !req.eadCardNumber().isBlank())
            p.setEadCardNumberEnc(encryptionService.encrypt(req.eadCardNumber()));

        // EAD metadata (non-sensitive)
        if (req.eadCategory()   != null) p.setEadCategory(req.eadCategory());
        if (req.eadExpiryDate() != null) p.setEadExpiryDate(parseDate(req.eadExpiryDate()));
        if (req.eadCaseNumber() != null) p.setEadCaseNumber(req.eadCaseNumber());

        // Notification preferences
        if (req.notificationEmailEnabled() != null) p.setNotificationEmailEnabled(req.notificationEmailEnabled());
        if (req.notificationSmsEnabled()   != null) p.setNotificationSmsEnabled(req.notificationSmsEnabled());
        if (req.notificationPhone()        != null) p.setNotificationPhone(req.notificationPhone());

        // USCIS / profile preferences
        if (req.uscisOnlineAccountNumber() != null) p.setUscisOnlineAccountNumber(req.uscisOnlineAccountNumber());
        if (req.preferredLanguage()        != null) p.setPreferredLanguage(req.preferredLanguage());

        // JSON sub-fields
        if (req.currentAddress() != null) p.setCurrentAddressJson(toJson(req.currentAddress()));
        if (req.education()      != null) p.setEducationJson(toJson(req.education()));
        if (req.employment()     != null) p.setEmploymentJson(toJson(req.employment()));
        if (req.dependents()     != null) p.setDependentsJson(toJson(req.dependents()));
        if (req.priorVisas()     != null) p.setPriorVisasJson(toJson(req.priorVisas()));

        if (req.notes() != null) p.setNotes(req.notes());
    }

    /**
     * Builds passport DTO list with decrypted numbers.
     * Auto-migrates legacy single-field data on first read if passportsJson is null.
     */
    private Object buildPassportsDto(CanonicalProfile p) {
        if (p.getPassportsJson() != null && !p.getPassportsJson().isBlank()) {
            return decryptPassportNumbers(parseJson(p.getPassportsJson()));
        }
        // Auto-migrate legacy single-field passport data to array format
        if (p.getPassportCountry() != null || p.getPassportNumberEnc() != null) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", UUID.randomUUID().toString());
            if (p.getPassportNumberEnc() != null) {
                try { entry.put("number", encryptionService.decrypt(p.getPassportNumberEnc())); }
                catch (Exception e) {
                    log.error("!!! passport decrypt failed during legacy migration: {}", e.getMessage());
                }
            }
            entry.put("country",     p.getPassportCountry());
            entry.put("issueDate",   p.getPassportIssueDate()  != null ? p.getPassportIssueDate().toString()  : null);
            entry.put("expiryDate",  p.getPassportExpiryDate() != null ? p.getPassportExpiryDate().toString() : null);
            entry.put("notes",       null);
            entry.put("documentIds", List.of());
            return List.of(entry);
        }
        return List.of();
    }

    /**
     * Builds travel entries DTO.
     * Auto-migrates legacy portOfEntry/i94Number/entryDate fields on first read.
     */
    private Object buildTravelEntriesDto(CanonicalProfile p) {
        if (p.getTravelEntriesJson() != null && !p.getTravelEntriesJson().isBlank()) {
            return parseJson(p.getTravelEntriesJson());
        }
        // Auto-migrate legacy single-field travel data
        if (p.getPortOfEntry() != null || p.getI94Number() != null || p.getEntryDate() != null) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id",           UUID.randomUUID().toString());
            entry.put("portOfEntry",  p.getPortOfEntry());
            entry.put("i94Number",    p.getI94Number());
            entry.put("entryDate",    p.getEntryDate() != null ? p.getEntryDate().toString() : null);
            entry.put("admittedUntil", null);
            entry.put("visaClass",    null);
            entry.put("notes",        null);
            entry.put("documentIds",  List.of());
            return List.of(entry);
        }
        return List.of();
    }

    /** Encrypts the 'number' field in each passport item → 'numberEnc'. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> encryptPassportNumbers(Object passports) {
        if (passports == null) return List.of();
        List<Map<String, Object>> list = (List<Map<String, Object>>) passports;
        return list.stream().map(item -> {
            Map<String, Object> copy = new LinkedHashMap<>(item);
            String number = (String) copy.remove("number");
            if (number != null && !number.isBlank()) {
                copy.put("numberEnc", encryptionService.encrypt(number));
            }
            // Items without a number change keep any existing numberEnc untouched
            return copy;
        }).collect(Collectors.toList());
    }

    /** Decrypts 'numberEnc' → 'number' in each passport item for the DTO response. */
    @SuppressWarnings("unchecked")
    private Object decryptPassportNumbers(Object parsed) {
        if (parsed == null) return List.of();
        List<Map<String, Object>> list = (List<Map<String, Object>>) parsed;
        return list.stream().map(item -> {
            Map<String, Object> copy = new LinkedHashMap<>(item);
            String enc = (String) copy.remove("numberEnc");
            if (enc != null && !enc.isBlank()) {
                try { copy.put("number", encryptionService.decrypt(enc)); }
                catch (Exception e) {
                    log.error("!!! passport decrypt failed in toDTO: {}", e.getMessage());
                    copy.put("number", null);
                }
            }
            return copy;
        }).collect(Collectors.toList());
    }

    /**
     * Keeps legacy single-field columns in sync with the current passport (highest issueDate).
     * FormMappingService reads these single fields directly, so they must stay accurate.
     */
    private void syncCurrentPassportToSingleFields(CanonicalProfile p, List<Map<String, Object>> encrypted) {
        encrypted.stream()
            .max(Comparator.comparing(
                m -> m.get("issueDate") != null ? (String) m.get("issueDate") : "",
                Comparator.nullsFirst(Comparator.naturalOrder())
            ))
            .ifPresent(curr -> {
                p.setPassportNumberEnc((String) curr.get("numberEnc"));
                p.setPassportCountry((String) curr.get("country"));
                p.setPassportIssueDate(parseDate((String) curr.get("issueDate")));
                p.setPassportExpiryDate(parseDate((String) curr.get("expiryDate")));
            });
    }

    /**
     * Keeps legacy single I-94 fields in sync with the most recent travel entry (highest entryDate).
     * FormMappingService reads portOfEntry / i94Number / entryDate directly.
     */
    @SuppressWarnings("unchecked")
    private void syncMostRecentTravelEntry(CanonicalProfile p, Object travelEntries) {
        if (travelEntries == null) return;
        List<Map<String, Object>> list = (List<Map<String, Object>>) travelEntries;
        list.stream()
            .max(Comparator.comparing(
                m -> m.get("entryDate") != null ? (String) m.get("entryDate") : "",
                Comparator.nullsFirst(Comparator.naturalOrder())
            ))
            .ifPresent(curr -> {
                p.setPortOfEntry((String) curr.get("portOfEntry"));
                p.setEntryDate(parseDate((String) curr.get("entryDate")));
                p.setI94Number((String) curr.get("i94Number"));
            });
    }

    // ── Field-level audit helpers ─────────────────────────────────────────────

    private Map<String, String> captureProfileSnapshot(CanonicalProfile p) {
        Map<String, String> snap = new LinkedHashMap<>();
        snap.put("legalFirstName",          p.getLegalFirstName());
        snap.put("legalLastName",           p.getLegalLastName());
        snap.put("middleName",              p.getMiddleName());
        snap.put("dateOfBirth",             p.getDateOfBirth()       != null ? p.getDateOfBirth().toString()       : null);
        snap.put("countryOfBirth",          p.getCountryOfBirth());
        snap.put("citizenshipCountry",      p.getCitizenshipCountry());
        snap.put("gender",                  p.getGender());
        snap.put("currentVisaType",         p.getCurrentVisaType());
        snap.put("currentVisaExpiry",       p.getCurrentVisaExpiry() != null ? p.getCurrentVisaExpiry().toString() : null);
        snap.put("phone",                   p.getPhone());
        snap.put("eadCategory",             p.getEadCategory());
        snap.put("eadExpiryDate",           p.getEadExpiryDate()     != null ? p.getEadExpiryDate().toString()     : null);
        snap.put("eadCaseNumber",           p.getEadCaseNumber());
        snap.put("uscisOnlineAccountNumber",p.getUscisOnlineAccountNumber());
        snap.put("preferredLanguage",       p.getPreferredLanguage());
        snap.put("notes",                   p.getNotes());
        // Sensitive — decrypt for change-detection; stored as $ prefix to distinguish
        snap.put("$alienNumber",    safeDecrypt(p.getAlienNumberEnc()));
        snap.put("$ssn",            safeDecrypt(p.getSsnEnc()));
        snap.put("$i94Number",      safeDecrypt(p.getI94NumberEnc()));
        snap.put("$eadCardNumber",  safeDecrypt(p.getEadCardNumberEnc()));
        return snap;
    }

    private void emitProfileFieldAudits(Map<String, String> old, UpdateProfileRequest req,
                                        Long profileId, Long actorUserId) {
        // Non-sensitive scalar fields
        emitIfChanged("legalFirstName",           old.get("legalFirstName"),           req.legalFirstName(),           profileId, actorUserId, false);
        emitIfChanged("legalLastName",            old.get("legalLastName"),            req.legalLastName(),            profileId, actorUserId, false);
        emitIfChanged("middleName",               old.get("middleName"),               req.middleName(),               profileId, actorUserId, false);
        emitIfChanged("dateOfBirth",              old.get("dateOfBirth"),              req.dateOfBirth(),              profileId, actorUserId, false);
        emitIfChanged("countryOfBirth",           old.get("countryOfBirth"),           req.countryOfBirth(),           profileId, actorUserId, false);
        emitIfChanged("citizenshipCountry",       old.get("citizenshipCountry"),       req.citizenshipCountry(),       profileId, actorUserId, false);
        emitIfChanged("gender",                   old.get("gender"),                   req.gender(),                   profileId, actorUserId, false);
        emitIfChanged("currentVisaType",          old.get("currentVisaType"),          req.currentVisaType(),          profileId, actorUserId, false);
        emitIfChanged("currentVisaExpiry",        old.get("currentVisaExpiry"),        req.currentVisaExpiry(),        profileId, actorUserId, false);
        emitIfChanged("phone",                    old.get("phone"),                    req.phone(),                    profileId, actorUserId, false);
        emitIfChanged("eadCategory",              old.get("eadCategory"),              req.eadCategory(),              profileId, actorUserId, false);
        emitIfChanged("eadExpiryDate",            old.get("eadExpiryDate"),            req.eadExpiryDate(),            profileId, actorUserId, false);
        emitIfChanged("eadCaseNumber",            old.get("eadCaseNumber"),            req.eadCaseNumber(),            profileId, actorUserId, false);
        emitIfChanged("uscisOnlineAccountNumber", old.get("uscisOnlineAccountNumber"), req.uscisOnlineAccountNumber(), profileId, actorUserId, false);
        emitIfChanged("preferredLanguage",        old.get("preferredLanguage"),        req.preferredLanguage(),        profileId, actorUserId, false);
        emitIfChanged("notes",                    old.get("notes"),                    req.notes(),                    profileId, actorUserId, false);

        // Sensitive fields — hash comparison via AuditService (isSensitive=true)
        if (req.alienNumber()   != null && !req.alienNumber().isBlank())
            emitIfChanged("alienNumber",   old.get("$alienNumber"),   req.alienNumber(),   profileId, actorUserId, true);
        if (req.ssn()           != null && !req.ssn().isBlank())
            emitIfChanged("ssn",           old.get("$ssn"),           req.ssn(),           profileId, actorUserId, true);
        if (req.i94Number()     != null && !req.i94Number().isBlank())
            emitIfChanged("i94Number",     old.get("$i94Number"),     req.i94Number(),     profileId, actorUserId, true);
        if (req.eadCardNumber() != null && !req.eadCardNumber().isBlank())
            emitIfChanged("eadCardNumber", old.get("$eadCardNumber"), req.eadCardNumber(), profileId, actorUserId, true);

        // JSON blobs — emit one aggregate event per updated blob (no diff; too large to store)
        Stream.of(
                new Object[]{"passports",     req.passports()},
                new Object[]{"travelEntries", req.travelEntries()},
                new Object[]{"currentAddress",req.currentAddress()},
                new Object[]{"education",     req.education()},
                new Object[]{"employment",    req.employment()},
                new Object[]{"dependents",    req.dependents()},
                new Object[]{"priorVisas",    req.priorVisas()}
        ).filter(pair -> pair[1] != null).forEach(pair ->
                auditService.appendCaseEvent(null, "CanonicalProfile", profileId,
                        (String) pair[0], "CHANGED", "direct_edit", "{\"updated\":true}", actorUserId));
    }

    private void emitIfChanged(String fieldKey, String oldVal, String newVal,
                                Long profileId, Long actorUserId, boolean isSensitive) {
        if (newVal == null) return;
        if (Objects.equals(oldVal, newVal)) return;
        auditService.appendFieldChange(null, "CanonicalProfile", profileId, fieldKey,
                oldVal, newVal, "direct_edit", isSensitive, actorUserId);
    }

    private String safeDecrypt(String enc) {
        if (enc == null || enc.isBlank()) return null;
        try { return encryptionService.decrypt(enc); }
        catch (Exception e) { return null; }
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        return LocalDate.parse(s);
    }

    private String toJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize field to JSON", e);
        }
    }

    private Object parseJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            log.warn("!!! Failed to parse stored JSON: {}", e.getMessage());
            return null;
        }
    }
}
