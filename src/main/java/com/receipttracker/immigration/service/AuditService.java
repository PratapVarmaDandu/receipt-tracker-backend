package com.receipttracker.immigration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.receipttracker.immigration.dto.*;
import com.receipttracker.immigration.model.*;
import com.receipttracker.immigration.repository.*;
import com.receipttracker.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Append-only audit service.
 * No update or delete methods exist — the audit log is permanent.
 * All append methods are non-fatal: they catch exceptions and log at ERROR level.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    @Autowired private ImmAuditEventRepository auditRepo;
    @Autowired private ImmFieldAuditEventRepository fieldAuditRepo;
    @Autowired private FormVersionAuditEventRepository formVersionAuditRepo;
    @Autowired private FilingPackageRepository filingPackageRepo;
    @Autowired private ImmigrationCaseRepository caseRepo;
    @Autowired private ObjectMapper objectMapper;

    // ── Existing case-level audit (unchanged) ─────────────────────────────────

    @Transactional
    public void append(ImmigrationCase c, User actor, String action, String detail, FeedVisibility visibility) {
        try {
            ImmAuditEvent event = new ImmAuditEvent();
            event.setImmigrationCase(c);
            event.setActor(actor);
            event.setAction(action);
            event.setDetail(detail);
            event.setVisibility(visibility);
            auditRepo.save(event);
            log.debug("Audit: case={} action={} actor={}", c.getId(), action, actor != null ? actor.getId() : "SYSTEM");
        } catch (Exception e) {
            log.error("!!! Failed to append audit event action={}: {}", action, e.getMessage());
        }
    }

    @Transactional
    public void appendSystem(ImmigrationCase c, String action, String detail) {
        append(c, null, action, detail, FeedVisibility.ALL);
    }

    // ── Field-level audit ─────────────────────────────────────────────────────

    /**
     * Records a single field change.
     * If isSensitive: SHA-256 hashes of old/new are stored; raw values are never persisted.
     * If not sensitive: old/new values are stored in detail JSON.
     */
    @Transactional
    public void appendFieldChange(Long caseId, String entityType, Long entityId,
                                  String fieldKey, String oldValue, String newValue,
                                  String source, boolean isSensitive, Long actorUserId) {
        try {
            ImmFieldAuditEvent e = new ImmFieldAuditEvent();
            e.setCaseId(caseId);
            e.setEntityType(entityType);
            e.setEntityId(entityId);
            e.setFieldKey(fieldKey);
            e.setActorUserId(actorUserId);
            e.setAction("CHANGED");
            e.setSource(source);
            e.setSensitive(isSensitive);

            if (isSensitive) {
                e.setOldValueHash(sha256(oldValue));
                e.setNewValueHash(sha256(newValue));
                e.setDetail(null);
            } else {
                e.setOldValueHash(null);
                e.setNewValueHash(null);
                e.setDetail(toJson(Map.of(
                        "fieldKey", fieldKey,
                        "old", oldValue != null ? oldValue : "",
                        "new", newValue != null ? newValue : ""
                )));
            }
            fieldAuditRepo.save(e);
        } catch (Exception ex) {
            log.error("!!! Failed to append field audit event fieldKey={}: {}", fieldKey, ex.getMessage());
        }
    }

    /**
     * Records a non-field event (scan, questionnaire submission, PDF generation, etc.)
     * on a case. action and source must match the enum values on ImmFieldAuditEvent.
     */
    @Transactional
    public void appendCaseEvent(Long caseId, String entityType, Long entityId,
                                String fieldKey, String action, String source,
                                String detail, Long actorUserId) {
        try {
            ImmFieldAuditEvent e = new ImmFieldAuditEvent();
            e.setCaseId(caseId);
            e.setEntityType(entityType);
            e.setEntityId(entityId);
            e.setFieldKey(fieldKey);
            e.setActorUserId(actorUserId);
            e.setAction(action);
            e.setSource(source);
            e.setSensitive(false);
            e.setDetail(detail);
            fieldAuditRepo.save(e);
        } catch (Exception ex) {
            log.error("!!! Failed to append case event action={}: {}", action, ex.getMessage());
        }
    }

    /**
     * Records a form version lifecycle event (DOWNLOADED, APPROVED, DEPRECATED, …).
     * Delegates to FormVersionAuditEventRepository so the existing imm_form_version_audit
     * table remains the single source of truth.
     */
    @Transactional
    public void appendFormVersionEvent(String formType, String editionDate, String action,
                                       Long actorUserId, String detail) {
        try {
            FormVersionAuditEvent evt = new FormVersionAuditEvent();
            evt.setFormType(formType);
            evt.setEditionDate(editionDate);
            evt.setAction(action);
            evt.setPerformedByUserId(actorUserId);
            evt.setDetail(detail);
            formVersionAuditRepo.save(evt);
        } catch (Exception e) {
            log.error("!!! Failed to append form version audit event formType={} action={}: {}",
                    formType, action, e.getMessage());
        }
    }

    /**
     * Records a PDF packet generation event.
     * formVersionsUsed is a list of {formType, versionId, editionDate} maps.
     */
    @Transactional
    public void appendPdfGeneration(Long packetId, Long caseId,
                                    List<Map<String, Object>> formVersionsUsed,
                                    Long generatedByUserId) {
        try {
            ImmFieldAuditEvent e = new ImmFieldAuditEvent();
            e.setCaseId(caseId);
            e.setEntityType("GeneratedPdfPacket");
            e.setEntityId(packetId);
            e.setFieldKey("pdf_packet");
            e.setActorUserId(generatedByUserId);
            e.setAction("PDF_GENERATED");
            e.setSource("system");
            e.setSensitive(false);
            e.setDetail(toJson(Map.of(
                    "packetId", packetId,
                    "formVersionsUsed", formVersionsUsed
            )));
            fieldAuditRepo.save(e);
        } catch (Exception ex) {
            log.error("!!! Failed to append PDF generation audit event packetId={}: {}", packetId, ex.getMessage());
        }
    }

    // ── Case audit query (ATTORNEY + FIRM_ADMIN) ──────────────────────────────

    private static final Set<String> DATA_CHANGE_ACTIONS = Set.of(
            "CHANGED", "SCANNED", "QUESTIONNAIRE_SUBMITTED");
    private static final Set<String> PDF_EVENT_ACTIONS = Set.of(
            "PDF_GENERATED", "ATTORNEY_APPROVED");

    /**
     * Returns the merged audit view for a case, grouped by type.
     * Requires READ_CASE + APPROVE_FORMS to be checked by the caller (controller).
     */
    @Transactional(readOnly = true)
    public CaseAuditDTO getCaseAudit(Long caseId) {
        ImmigrationCase immCase = caseRepo.findById(caseId)
                .orElseThrow(() -> new RuntimeException("Case not found: " + caseId));

        // 1. Case-level events (existing ImmAuditEvent table)
        List<ActivityFeedItemDTO> caseEvents = auditRepo
                .findVisibleEvents(immCase, List.of(FeedVisibility.values()))
                .stream()
                .map(e -> new ActivityFeedItemDTO(
                        e.getId(),
                        e.getAction(),
                        e.getActor() != null ? e.getActor().getName() : "System",
                        e.getDetail(),
                        e.getCreatedAt()))
                .collect(Collectors.toList());

        // 2. Field-level events for this case
        List<ImmFieldAuditEvent> allFieldEvents = fieldAuditRepo.findByCaseIdOrderByCreatedAtDesc(caseId);

        List<ImmFieldAuditEventDTO> dataChanges = allFieldEvents.stream()
                .filter(e -> DATA_CHANGE_ACTIONS.contains(e.getAction()))
                .map(this::toFieldAuditDTO)
                .collect(Collectors.toList());

        List<ImmFieldAuditEventDTO> pdfEvents = allFieldEvents.stream()
                .filter(e -> PDF_EVENT_ACTIONS.contains(e.getAction()))
                .map(this::toFieldAuditDTO)
                .collect(Collectors.toList());

        // 3. Form version events: collect form types from all packages for this case
        Set<String> formTypes = filingPackageRepo.findByCaseIdOrderByCreatedAtDesc(caseId)
                .stream()
                .flatMap(pkg -> {
                    if (pkg.getSelectedFormTypesJson() == null) return java.util.stream.Stream.empty();
                    try {
                        List<String> types = objectMapper.readValue(
                                pkg.getSelectedFormTypesJson(),
                                new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
                        return types.stream();
                    } catch (Exception e) {
                        log.warn("Could not parse selectedFormTypesJson for package {}: {}", pkg.getId(), e.getMessage());
                        return java.util.stream.Stream.empty();
                    }
                })
                .collect(Collectors.toSet());

        List<FormVersionAuditEventDTO> formVersionEvents = formTypes.stream()
                .flatMap(ft -> formVersionAuditRepo.findByFormTypeOrderByCreatedAtDesc(ft).stream())
                .map(e -> new FormVersionAuditEventDTO(e.getId(), e.getFormType(), e.getEditionDate(),
                        e.getAction(), e.getPerformedByUserId(), e.getDetail(), e.getCreatedAt()))
                .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
                .collect(Collectors.toList());

        return new CaseAuditDTO(caseEvents, dataChanges, formVersionEvents, pdfEvents);
    }

    private ImmFieldAuditEventDTO toFieldAuditDTO(ImmFieldAuditEvent e) {
        return new ImmFieldAuditEventDTO(
                e.getId(), e.getCaseId(), e.getEntityType(), e.getEntityId(),
                e.getFieldKey(), e.getActorUserId(), e.getAction(), e.getSource(),
                e.getOldValueHash(), e.getNewValueHash(), e.isSensitive(),
                e.getDetail(), e.getCreatedAt());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    static String sha256(String value) {
        if (value == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            log.error("!!! SHA-256 computation failed: {}", e.getMessage());
            return null;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("!!! AuditService.toJson failed: {}", e.getMessage());
            return null;
        }
    }
}
