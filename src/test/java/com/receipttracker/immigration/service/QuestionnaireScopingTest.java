package com.receipttracker.immigration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.receipttracker.immigration.model.question.CanonicalQuestion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session 3 scoping audit: proves the "nothing else, nothing less" contract for
 * {@link FilingPackageService#create}'s questionnaire building — for any set of
 * selected form types, an owner's questionnaire spec must be exactly the set of
 * that owner's non-derived, non-reviewOnly questions whose formsUsing intersects
 * the selection. Exercises {@link CanonicalQuestionRegistry#getQuestionnaireSpecByOwner}
 * directly (the same method FilingPackageService.create() calls) against the real
 * canonical-questions.json — no mocking of the questions themselves.
 */
class QuestionnaireScopingTest {

    private CanonicalQuestionRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        registry = new CanonicalQuestionRegistry();
        ReflectionTestUtils.setField(registry, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(registry, "derivationRegistry", new DerivationRegistry());
        registry.init();
    }

    /** Every distinct form tag referenced anywhere in formsUsing[] across the catalog. */
    private Set<String> allFormTags() {
        Set<String> tags = new LinkedHashSet<>();
        for (CanonicalQuestion q : registry.getAllQuestions()) {
            if (q.getFormsUsing() != null) tags.addAll(q.getFormsUsing());
        }
        return tags;
    }

    /** Ground-truth expected key set, computed independently of getQuestionnaireSpecByOwner. */
    private Set<String> expectedKeys(String formType, String owner) {
        Set<String> keys = new LinkedHashSet<>();
        for (CanonicalQuestion q : registry.getAllQuestions()) {
            if (q.getFormsUsing() == null || !q.getFormsUsing().contains(formType)) continue;
            if (!owner.equals(q.getOwner())) continue;
            if (q.isDerived() || q.isReviewOnly()) continue;
            keys.add(q.getKey());
        }
        return keys;
    }

    // ── Exactness: spec(owner) == {q.key : q.owner=owner, form ∈ formsUsing, !derived, !reviewOnly} ──

    @Test
    void i129QuestionnaireSpecIsExactPerOwner() {
        List<CanonicalQuestion> questions = registry.getQuestionsForForms(List.of("I129"));
        Map<String, List<String>> spec = registry.getQuestionnaireSpecByOwner(questions);

        for (String owner : List.of("BENEFICIARY", "EMPLOYER", "ATTORNEY")) {
            Set<String> expected = expectedKeys("I129", owner);
            Set<String> actual = new LinkedHashSet<>(spec.getOrDefault(owner, List.of()));
            assertThat(actual)
                    .as("I129 %s questionnaire spec", owner)
                    .containsExactlyInAnyOrderElementsOf(expected);
        }
    }

    @Test
    void everyFormTagProducesExactSpecPerOwner() {
        // Broad sweep across every form tag the catalog knows about (not just I129),
        // so a future form gaining questions is covered automatically.
        for (String formType : allFormTags()) {
            List<CanonicalQuestion> questions = registry.getQuestionsForForms(List.of(formType));
            Map<String, List<String>> spec = registry.getQuestionnaireSpecByOwner(questions);

            for (String owner : List.of("BENEFICIARY", "EMPLOYER", "ATTORNEY")) {
                Set<String> expected = expectedKeys(formType, owner);
                Set<String> actual = new LinkedHashSet<>(spec.getOrDefault(owner, List.of()));
                assertThat(actual)
                        .as("%s %s questionnaire spec", formType, owner)
                        .containsExactlyInAnyOrderElementsOf(expected);
            }
        }
    }

    @Test
    void multiFormSelectionUnionsCorrectlyPerOwner() {
        // A package covering two forms at once must union each form's questions per
        // owner — not silently drop one form's fields.
        List<String> forms = List.of("I129", "I485");
        List<CanonicalQuestion> questions = registry.getQuestionsForForms(forms);
        Map<String, List<String>> spec = registry.getQuestionnaireSpecByOwner(questions);

        for (String owner : List.of("BENEFICIARY", "EMPLOYER", "ATTORNEY")) {
            Set<String> expected = new LinkedHashSet<>();
            expected.addAll(expectedKeys("I129", owner));
            expected.addAll(expectedKeys("I485", owner));
            Set<String> actual = new LinkedHashSet<>(spec.getOrDefault(owner, List.of()));
            assertThat(actual)
                    .as("[I129,I485] %s questionnaire spec", owner)
                    .containsExactlyInAnyOrderElementsOf(expected);
        }
    }

    // ── Derived / reviewOnly must never leak into any questionnaire ──────────────

    @Test
    void noDerivedOrReviewOnlyQuestionEverAppearsInAnyQuestionnaireSpec() {
        for (String formType : allFormTags()) {
            List<CanonicalQuestion> questions = registry.getQuestionsForForms(List.of(formType));
            Map<String, List<String>> spec = registry.getQuestionnaireSpecByOwner(questions);

            Set<String> derivedOrReviewOnlyKeys = questions.stream()
                    .filter(q -> q.isDerived() || q.isReviewOnly())
                    .map(CanonicalQuestion::getKey)
                    .collect(java.util.stream.Collectors.toSet());
            if (derivedOrReviewOnlyKeys.isEmpty()) continue; // nothing to exclude for this form tag

            for (List<String> keys : spec.values()) {
                assertThat(keys)
                        .as("%s questionnaire spec must exclude derived/reviewOnly keys", formType)
                        .doesNotContainAnyElementsOf(derivedOrReviewOnlyKeys);
            }
        }
    }

    @Test
    void i129SpecOmitsKnownDerivedAndReviewOnlyKeys() {
        List<CanonicalQuestion> questions = registry.getQuestionsForForms(List.of("I129"));
        Map<String, List<String>> spec = registry.getQuestionnaireSpecByOwner(questions);
        List<String> attorneySpec = spec.getOrDefault("ATTORNEY", List.of());

        // Derived — computed from case data, never asked
        assertThat(attorneySpec).doesNotContain(
                "petition.classificationSymbol", "petition.basisForClassification",
                "petition.requestedAction", "petition.totalWorkers");

        // reviewOnly — attorney attestations set via override at review time
        assertThat(attorneySpec).doesNotContain(
                "signatory.lastName", "signatory.firstName", "signatory.title",
                "signatory.signatureDate", "signatory.phone", "signatory.email",
                "petition.exportControlLicenseRequired",
                "petition.priorClassificationGranted", "petition.priorClassificationDenied");
    }

    // ── No cross-owner leakage or duplicates ──────────────────────────────────────

    @Test
    void specKeysAreOwnedByExactlyOneOwnerAndUnique() {
        List<CanonicalQuestion> questions = registry.getQuestionsForForms(List.of("I129"));
        Map<String, List<String>> spec = registry.getQuestionnaireSpecByOwner(questions);

        for (Map.Entry<String, List<String>> entry : spec.entrySet()) {
            String owner = entry.getKey();
            List<String> keys = entry.getValue();

            // No duplicates within an owner's spec
            assertThat(keys).doesNotHaveDuplicates();

            // Every key in this owner's spec really is owned by this owner
            for (String key : keys) {
                CanonicalQuestion q = registry.findByKey(key).orElseThrow();
                assertThat(q.getOwner()).as("owner of %s", key).isEqualTo(owner);
            }
        }

        // No key appears under more than one owner
        List<String> allKeys = spec.values().stream().flatMap(List::stream).toList();
        assertThat(allKeys).doesNotHaveDuplicates();
    }
}
