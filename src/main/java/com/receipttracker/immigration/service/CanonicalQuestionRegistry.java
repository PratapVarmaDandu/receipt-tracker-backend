package com.receipttracker.immigration.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.receipttracker.immigration.model.question.CanonicalQuestion;
import com.receipttracker.immigration.model.question.FormFieldEntry;
import com.receipttracker.immigration.model.question.FormFieldMapping;
import com.receipttracker.immigration.model.question.FormSectionMapping;
import com.receipttracker.immigration.model.question.RepeatGroupSpec;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Loads and caches the canonical question registry and per-form field mappings
 * from classpath at startup.  Thread-safe after {@code @PostConstruct} completes.
 *
 * JSON locations (classpath):
 *   immigration/questions/canonical-questions.json
 *   immigration/questions/form-field-mappings/*.json
 */
@Service
public class CanonicalQuestionRegistry {

    private static final Logger log = LoggerFactory.getLogger(CanonicalQuestionRegistry.class);

    private static final String QUESTIONS_PATH    = "immigration/questions/canonical-questions.json";
    private static final String MAPPINGS_PATTERN  = "classpath:immigration/questions/form-field-mappings/*.json";

    @Autowired private ObjectMapper objectMapper;
    @Autowired private DerivationRegistry derivationRegistry;

    /** All questions indexed by key for O(1) lookup */
    private Map<String, CanonicalQuestion> byKey = Collections.emptyMap();

    /** Ordered list (preserves JSON array order) */
    private List<CanonicalQuestion> allQuestions = Collections.emptyList();

    /** Form field mappings keyed by formType string (e.g. "I129") */
    private Map<String, FormFieldMapping> formMappings = Collections.emptyMap();

    // ── Initialisation ────────────────────────────────────────────────────────

    @PostConstruct
    public void init() throws IOException {
        loadQuestions();
        loadFormMappings();
        log.info("CanonicalQuestionRegistry ready: {} questions, {} form mappings",
                 allQuestions.size(), formMappings.size());
    }

    private void loadQuestions() throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(QUESTIONS_PATH)) {
            if (is == null) {
                log.warn("canonical-questions.json not found on classpath at {}", QUESTIONS_PATH);
                return;
            }
            List<CanonicalQuestion> list = objectMapper.readValue(is, new TypeReference<>() {});
            allQuestions = Collections.unmodifiableList(list);
            byKey = list.stream().collect(Collectors.toUnmodifiableMap(CanonicalQuestion::getKey, q -> q));
            validateStorageConfig(list);
            validateDerivations(list);
            validateRepeatGroups(list);
            log.info("Loaded {} canonical questions ({} generic-storage, {} derived, {} review-only)",
                     list.size(),
                     list.stream().filter(CanonicalQuestion::isGenericStorage).count(),
                     list.stream().filter(CanonicalQuestion::isDerived).count(),
                     list.stream().filter(CanonicalQuestion::isReviewOnly).count());
        }
    }

    /** Warns on unknown pdfFieldType values in bundled mappings — an unknown type fills as text. */
    private void validateFieldTypes(FormFieldMapping ffm) {
        if (ffm.getSections() == null) return;
        for (FormSectionMapping section : ffm.getSections().values()) {
            if (section.getFields() == null) continue;
            for (FormFieldEntry entry : section.getFields()) {
                if (entry.getPdfFieldType() != null
                        && !PdfFieldApplier.VALID_FIELD_TYPES.contains(entry.getPdfFieldType())) {
                    log.warn("Form mapping {}: field '{}' has unknown pdfFieldType '{}' — expected one of {}",
                             ffm.getFormType(), entry.getPdfFieldName(), entry.getPdfFieldType(),
                             PdfFieldApplier.VALID_FIELD_TYPES);
                }
                String problem = repeatEntryProblem(entry);
                if (problem != null) {
                    log.warn("Form mapping {}: field '{}' — {}",
                             ffm.getFormType(), entry.getPdfFieldName(), problem);
                }
            }
        }
    }

    /**
     * Validates a mapping entry's repeat-group binding against the loaded
     * questions. Returns a description of the problem, or null when valid.
     * Shared with FormVersionService.uploadMapping(), which hard-fails on it.
     */
    public String repeatEntryProblem(FormFieldEntry entry) {
        CanonicalQuestion cq = entry.getQuestionKey() != null
                ? byKey.get(entry.getQuestionKey()) : null;
        boolean isListQuestion = cq != null && cq.isList();

        if (!entry.isRepeatEntry() && entry.getItemField() == null) {
            // Plain scalar entry — but a LIST question can't fill one field whole
            return isListQuestion
                    ? "questionKey '" + entry.getQuestionKey()
                      + "' is a LIST question and needs repeatIndex + itemField"
                    : null;
        }
        if (entry.getRepeatIndex() == null || entry.getItemField() == null || entry.getItemField().isBlank()) {
            return "repeatIndex and itemField must be set together";
        }
        if (cq == null) return null; // unknown key is reported separately
        if (!isListQuestion) {
            return "repeatIndex/itemField set but questionKey '" + entry.getQuestionKey()
                   + "' is not a LIST question";
        }
        RepeatGroupSpec spec = cq.getRepeatGroup();
        if (spec == null) return null; // missing repeatGroup warned at question load
        if (entry.getRepeatIndex() < 0 || entry.getRepeatIndex() >= spec.effectiveMaxRows()) {
            return "repeatIndex " + entry.getRepeatIndex() + " out of range (maxRows "
                   + spec.effectiveMaxRows() + ")";
        }
        boolean known = spec.getItemFields() != null && spec.getItemFields().stream()
                .anyMatch(f -> entry.getItemField().equals(f.getKey()));
        if (!known) {
            return "itemField '" + entry.getItemField() + "' is not declared on '"
                   + entry.getQuestionKey() + "'";
        }
        return null;
    }

    /** Warns on malformed repeat-group config — such questions degrade to no prefill/fill. */
    private void validateRepeatGroups(List<CanonicalQuestion> list) {
        for (CanonicalQuestion q : list) {
            RepeatGroupSpec rg = q.getRepeatGroup();
            if (q.isList() && rg == null) {
                log.warn("Question '{}' is type LIST but has no repeatGroup block", q.getKey());
                continue;
            }
            if (!q.isList() && rg != null) {
                log.warn("Question '{}' has a repeatGroup but type '{}' (expected LIST)", q.getKey(), q.getType());
            }
            if (rg == null) continue;
            if (q.isList() && !q.isGenericStorage()) {
                log.warn("Question '{}' is LIST but storage '{}' — LIST questions must use storage=\"generic\"",
                         q.getKey(), q.getStorage());
            }
            if (rg.getItemFields() == null || rg.getItemFields().isEmpty()) {
                log.warn("Question '{}' repeatGroup has no itemFields", q.getKey());
            }
            if (rg.getMaxRows() <= 0) {
                log.warn("Question '{}' repeatGroup has maxRows {} — using default {}",
                         q.getKey(), rg.getMaxRows(), RepeatGroupSpec.DEFAULT_MAX_ROWS);
            }
            if (rg.getSourceList() != null && !DataResolver.KNOWN_SOURCE_LISTS.contains(rg.getSourceList())) {
                log.warn("Question '{}' repeatGroup names unknown sourceList '{}' — expected one of {}",
                         q.getKey(), rg.getSourceList(), DataResolver.KNOWN_SOURCE_LISTS);
            }
        }
    }

    private static final Set<String> VALID_STORAGE = Set.of("typed", "generic");
    private static final Set<String> VALID_SUBJECT_SCOPES =
            Set.of("BENEFICIARY", "EMPLOYER_ORG", "LAW_FIRM_ORG", "CASE");

    /** Warns loudly on config typos — a bad storage value silently degrades to typed. */
    private void validateStorageConfig(List<CanonicalQuestion> list) {
        for (CanonicalQuestion q : list) {
            if (q.getStorage() != null && !VALID_STORAGE.contains(q.getStorage())) {
                log.warn("Question '{}' has unknown storage '{}' — expected one of {}",
                         q.getKey(), q.getStorage(), VALID_STORAGE);
            }
            if (q.getSubjectScope() != null && !VALID_SUBJECT_SCOPES.contains(q.getSubjectScope())) {
                log.warn("Question '{}' has unknown subjectScope '{}' — expected one of {}",
                         q.getKey(), q.getSubjectScope(), VALID_SUBJECT_SCOPES);
            }
        }
    }

    /** Warns on unknown derivation names — such questions resolve to no value. */
    private void validateDerivations(List<CanonicalQuestion> list) {
        for (CanonicalQuestion q : list) {
            if (q.isDerived() && !derivationRegistry.knows(q.getDerivation())) {
                log.warn("Question '{}' names unknown derivation '{}' — expected one of {}",
                         q.getKey(), q.getDerivation(), derivationRegistry.names());
            }
            if (q.isDerived() && q.isReviewOnly()) {
                log.warn("Question '{}' is both derived and reviewOnly — reviewOnly wins: "
                         + "the derivation is never evaluated", q.getKey());
            }
        }
    }

    private void loadFormMappings() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources(MAPPINGS_PATTERN);
        Map<String, FormFieldMapping> map = new LinkedHashMap<>();
        for (Resource res : resources) {
            try (InputStream is = res.getInputStream()) {
                FormFieldMapping ffm = objectMapper.readValue(is, FormFieldMapping.class);
                if (ffm.getFormType() != null) {
                    validateFieldTypes(ffm);
                    map.put(ffm.getFormType(), ffm);
                    log.debug("Loaded form mapping for {}", ffm.getFormType());
                }
            } catch (Exception e) {
                log.warn("Could not load form mapping from {}: {}", res.getFilename(), e.getMessage());
            }
        }
        formMappings = Collections.unmodifiableMap(map);
    }

    // ── Query API ─────────────────────────────────────────────────────────────

    /**
     * Returns deduplicated, ordered list of canonical questions needed by any of the
     * given form types.  Order follows the original JSON array order.
     */
    public List<CanonicalQuestion> getQuestionsForForms(List<String> formTypes) {
        if (formTypes == null || formTypes.isEmpty()) return List.of();
        Set<String> formSet = new HashSet<>(formTypes);
        // Preserve JSON order; LinkedHashSet deduplicates while keeping insertion order
        LinkedHashMap<String, CanonicalQuestion> seen = new LinkedHashMap<>();
        for (CanonicalQuestion q : allQuestions) {
            if (q.getFormsUsing() != null && !Collections.disjoint(q.getFormsUsing(), formSet)) {
                seen.put(q.getKey(), q);
            }
        }
        return List.copyOf(seen.values());
    }

    /**
     * Groups a list of questions by their {@code owner} field.
     * Keys: BENEFICIARY, EMPLOYER, ATTORNEY (and any future values).
     */
    public Map<String, List<CanonicalQuestion>> getQuestionsByOwner(List<CanonicalQuestion> questions) {
        return questions.stream().collect(
                Collectors.groupingBy(
                        q -> q.getOwner() != null ? q.getOwner() : "UNKNOWN",
                        LinkedHashMap::new,
                        Collectors.toList()
                )
        );
    }

    /**
     * Builds the per-owner questionnaire spec (owner -> ordered question keys) for a
     * resolved question set: {@link #getQuestionsByOwner} minus derived (computed, never
     * asked) and reviewOnly (attorney attestation, set via override) questions. This is
     * the exact "nothing else, nothing less" scoping contract — an owner's questionnaire
     * must contain precisely the non-derived, non-reviewOnly questions they own among the
     * questions returned by {@link #getQuestionsForForms}. Omits owners left with no keys.
     */
    public Map<String, List<String>> getQuestionnaireSpecByOwner(List<CanonicalQuestion> questions) {
        Map<String, List<CanonicalQuestion>> byOwner = getQuestionsByOwner(questions);
        Map<String, List<String>> spec = new LinkedHashMap<>();
        for (Map.Entry<String, List<CanonicalQuestion>> entry : byOwner.entrySet()) {
            List<String> keys = entry.getValue().stream()
                    .filter(cq -> !cq.isDerived() && !cq.isReviewOnly())
                    .map(CanonicalQuestion::getKey)
                    .toList();
            if (!keys.isEmpty()) spec.put(entry.getKey(), keys);
        }
        return spec;
    }

    /**
     * Groups a list of questions by their {@code friendlySection} field.
     * Preserves insertion order within each group.
     */
    public Map<String, List<CanonicalQuestion>> getQuestionsBySection(List<CanonicalQuestion> questions) {
        return questions.stream().collect(
                Collectors.groupingBy(
                        q -> q.getFriendlySection() != null ? q.getFriendlySection() : "other",
                        LinkedHashMap::new,
                        Collectors.toList()
                )
        );
    }

    /**
     * Returns all question keys that map to a PDF field in the given form, in section order.
     * Useful for the PDF generation layer to know which fields to populate and in what order.
     */
    public List<FormFieldEntry> getOrderedFieldsForForm(String formType) {
        FormFieldMapping ffm = formMappings.get(formType);
        if (ffm == null || ffm.getSections() == null) return List.of();
        return ffm.getSections().values().stream()
                .filter(s -> s.getFields() != null)
                .flatMap(s -> s.getFields().stream())
                .toList();
    }

    // ── Direct lookups ────────────────────────────────────────────────────────

    public Optional<CanonicalQuestion> findByKey(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    public List<CanonicalQuestion> getAllQuestions() {
        return allQuestions;
    }

    public Optional<FormFieldMapping> getFormMapping(String formType) {
        return Optional.ofNullable(formMappings.get(formType));
    }

    public Set<String> getLoadedFormTypes() {
        return formMappings.keySet();
    }

    /**
     * Human-readable label for a friendlySection ID.
     * Used by UI layers that only have the section ID.
     */
    public static String sectionLabel(String sectionId) {
        return SECTION_LABELS.getOrDefault(sectionId, sectionId);
    }

    private static final Map<String, String> SECTION_LABELS = Map.ofEntries(
        Map.entry("personal_info",       "Personal Information"),
        Map.entry("passport_id",         "Passport & I-94"),
        Map.entry("current_status",      "Current Immigration Status"),
        Map.entry("company_info",        "Company Information"),
        Map.entry("job_details",         "Job & Position Details"),
        Map.entry("employment_history",  "Employment History"),
        Map.entry("education",           "Education Background"),
        Map.entry("family_dependents",   "Family & Dependents"),
        Map.entry("ead_info",            "Work Authorization (EAD)"),
        Map.entry("notification_prefs",  "Notification Preferences"),
        Map.entry("petition_info",       "Petition Information")
    );
}
