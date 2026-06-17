package com.receipttracker.immigration.service;

import com.receipttracker.immigration.dto.KeyDateDTO;
import com.receipttracker.immigration.model.*;
import com.receipttracker.immigration.repository.CanonicalProfileRepository;
import com.receipttracker.immigration.repository.ImmigrationCaseRepository;
import com.receipttracker.immigration.repository.KeyDateRepository;
import com.receipttracker.model.User;
import com.receipttracker.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class KeyDateService {

    private static final Logger log = LoggerFactory.getLogger(KeyDateService.class);

    @Autowired private KeyDateRepository keyDateRepo;
    @Autowired private ImmigrationCaseRepository caseRepo;
    @Autowired private CanonicalProfileRepository profileRepo;
    @Autowired private PermissionService permissionService;
    @Autowired private UserRepository userRepo;

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        OAuth2User principal = (OAuth2User) auth.getPrincipal();
        String googleId = principal.getAttribute("sub");
        return userRepo.findByGoogleId(googleId)
                .orElseThrow(() -> new RuntimeException("Authenticated user not found"));
    }

    @Transactional(readOnly = true)
    public List<KeyDateDTO> listForCase(Long caseId) {
        log.info(">>> listForCase() caseId={}", caseId);
        User user = currentUser();
        permissionService.requireAccess(user, caseId, GrantScope.READ_CASE);

        ImmigrationCase c = caseRepo.findById(caseId)
                .orElseThrow(() -> new RuntimeException("Case not found: " + caseId));

        return keyDateRepo.findByImmigrationCaseOrderByDateAsc(c)
                .stream().map(this::toDTO).toList();
    }

    /**
     * Syncs auto-computed key dates from the case's priority date and beneficiary profile.
     * Only upserts dates where the source data exists; does not touch manually-added dates.
     */
    @Transactional
    public List<KeyDateDTO> syncFromProfile(Long caseId) {
        log.info(">>> syncFromProfile() caseId={}", caseId);
        User user = currentUser();
        permissionService.requireAccess(user, caseId, GrantScope.WRITE_CASE);

        ImmigrationCase c = caseRepo.findById(caseId)
                .orElseThrow(() -> new RuntimeException("Case not found: " + caseId));

        CanonicalProfile profile = profileRepo.findByBeneficiary(c.getBeneficiary()).orElse(null);

        // Map of KeyDateType → source date from profile/case
        Map<KeyDateType, LocalDate> sources = new java.util.LinkedHashMap<>();

        if (c.getPriorityDate() != null)
            sources.put(KeyDateType.PRIORITY_DATE, c.getPriorityDate());

        if (profile != null) {
            if (profile.getPassportExpiryDate() != null)
                sources.put(KeyDateType.PASSPORT_EXPIRY, profile.getPassportExpiryDate());
            if (profile.getCurrentVisaExpiry() != null)
                sources.put(KeyDateType.VISA_STAMP_EXPIRY, profile.getCurrentVisaExpiry());
        }

        List<KeyDate> saved = new ArrayList<>();
        for (var entry : sources.entrySet()) {
            KeyDateType type = entry.getKey();
            LocalDate date = entry.getValue();
            KeyDate kd = keyDateRepo.findByImmigrationCaseAndDateType(c, type)
                    .orElseGet(() -> { KeyDate k = new KeyDate(); k.setImmigrationCase(c); k.setDateType(type); return k; });
            kd.setDate(date);
            kd.setAutoComputed(true);
            saved.add(keyDateRepo.save(kd));
        }

        return listForCase(caseId);
    }

    @Transactional
    public KeyDateDTO addManual(Long caseId, String dateTypeStr, String dateStr, String label, String notes) {
        User user = currentUser();
        permissionService.requireAccess(user, caseId, GrantScope.WRITE_CASE);

        ImmigrationCase c = caseRepo.findById(caseId)
                .orElseThrow(() -> new RuntimeException("Case not found: " + caseId));

        KeyDateType dateType;
        try { dateType = KeyDateType.valueOf(dateTypeStr); }
        catch (IllegalArgumentException e) { throw new RuntimeException("Unknown date type: " + dateTypeStr); }

        KeyDate kd = new KeyDate();
        kd.setImmigrationCase(c);
        kd.setDateType(dateType);
        kd.setDate(LocalDate.parse(dateStr));
        kd.setLabel(label);
        kd.setNotes(notes);
        kd.setAutoComputed(false);

        return toDTO(keyDateRepo.save(kd));
    }

    @Transactional
    public void delete(Long caseId, Long keyDateId) {
        User user = currentUser();
        permissionService.requireAccess(user, caseId, GrantScope.WRITE_CASE);
        KeyDate kd = keyDateRepo.findById(keyDateId)
                .orElseThrow(() -> new RuntimeException("KeyDate not found: " + keyDateId));
        if (!kd.getImmigrationCase().getId().equals(caseId))
            throw new RuntimeException("KeyDate does not belong to case " + caseId);
        keyDateRepo.delete(kd);
    }

    KeyDateDTO toDTO(KeyDate kd) {
        long daysUntil = ChronoUnit.DAYS.between(LocalDate.now(), kd.getDate());
        String urgency = daysUntil < 0 ? "OVERDUE"
                : daysUntil <= 30 ? "DUE_SOON"
                : "UPCOMING";
        String displayLabel = kd.getLabel() != null ? kd.getLabel()
                : humanLabel(kd.getDateType().name());
        return new KeyDateDTO(
                kd.getId(), kd.getImmigrationCase().getId(),
                kd.getDateType().name(), displayLabel,
                kd.getDate().toString(), daysUntil, urgency,
                kd.isAutoComputed(), kd.getNotes(), kd.getCreatedAt()
        );
    }

    private String humanLabel(String enumName) {
        StringBuilder sb = new StringBuilder();
        for (String w : enumName.split("_")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0)));
            sb.append(w.substring(1).toLowerCase());
        }
        return sb.toString();
    }
}
