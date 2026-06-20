package com.receipttracker.immigration.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.receipttracker.immigration.model.question.CanonicalQuestion;
import com.receipttracker.immigration.model.question.FormFieldEntry;
import com.receipttracker.immigration.model.question.FormFieldMapping;
import com.receipttracker.immigration.model.question.FormSectionMapping;
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
            log.info("Loaded {} canonical questions", list.size());
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

    private static final Map<String, String> SECTION_LABELS = Map.of(
        "personal_info",       "Personal Information",
        "passport_id",         "Passport & I-94",
        "current_status",      "Current Immigration Status",
        "company_info",        "Company Information",
        "job_details",         "Job & Position Details",
        "employment_history",  "Employment History",
        "education",           "Education Background",
        "family_dependents",   "Family & Dependents",
        "ead_info",            "Work Authorization (EAD)",
        "notification_prefs",  "Notification Preferences"
    );
}
