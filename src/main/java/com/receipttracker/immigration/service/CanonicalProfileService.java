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

        applyUpdate(profile, req);
        CanonicalProfile saved = profileRepo.save(profile);
        log.info("<<< updateForCurrentUser() profileId={}", saved.getId());
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
                p.getDateOfBirth()     != null ? p.getDateOfBirth().toString()     : null,
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
