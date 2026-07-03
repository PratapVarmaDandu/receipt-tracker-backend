package com.receipttracker.immigration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.receipttracker.immigration.model.question.CanonicalQuestion;
import com.receipttracker.immigration.model.question.ResolvedValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5 review-only questions: config loading, prefill exclusion,
 * and their required-flag contract with pre-generation check 4.
 */
class ReviewOnlyQuestionTest {

    private CanonicalQuestionRegistry registry;
    private DataResolver dataResolver;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        registry = new CanonicalQuestionRegistry();
        ReflectionTestUtils.setField(registry, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(registry, "derivationRegistry", new DerivationRegistry());
        registry.init(); // loads canonical-questions.json + form mappings from classpath

        dataResolver = new DataResolver();
        ReflectionTestUtils.setField(dataResolver, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(dataResolver, "derivationRegistry", new DerivationRegistry());
    }

    // ── Config loading ────────────────────────────────────────────────────────

    @Test
    void reviewOnlyQuestionsLoadFromConfig() {
        CanonicalQuestion signatory = registry.findByKey("signatory.lastName").orElseThrow();
        assertThat(signatory.isReviewOnly()).isTrue();
        assertThat(signatory.getOwner()).isEqualTo("ATTORNEY");
        assertThat(signatory.isRequired()).isTrue();

        CanonicalQuestion ear = registry.findByKey("petition.exportControlLicenseRequired").orElseThrow();
        assertThat(ear.isReviewOnly()).isTrue();
        assertThat(ear.getType()).isEqualTo("BOOLEAN");
    }

    @Test
    void allReviewOnlyQuestionsAreAttorneyOwnedAndNeverDerived() {
        List<CanonicalQuestion> reviewOnly = registry.getAllQuestions().stream()
                .filter(CanonicalQuestion::isReviewOnly).toList();
        assertThat(reviewOnly).isNotEmpty();
        assertThat(reviewOnly).allSatisfy(q -> {
            assertThat(q.getOwner()).isEqualTo("ATTORNEY");
            assertThat(q.isDerived()).isFalse();
            assertThat(q.isList()).isFalse();
        });
    }

    @Test
    void reviewOnlyQuestionsAreIncludedForTheirForms() {
        // Pre-generation check 4 iterates getQuestionsForForms() and must see
        // required reviewOnly questions — they may not be filtered at this level
        List<CanonicalQuestion> i129 = registry.getQuestionsForForms(List.of("I129"));
        assertThat(i129).extracting(CanonicalQuestion::getKey)
                .contains("signatory.lastName", "signatory.firstName", "signatory.title", "signatory.signatureDate",
                          "petition.exportControlLicenseRequired");
    }

    // ── Prefill exclusion ─────────────────────────────────────────────────────

    @Test
    void reviewOnlyQuestionNeverResolvesAPrefill() {
        CanonicalQuestion q = registry.findByKey("signatory.lastName").orElseThrow();
        // Even a matching generic-store value must not leak into a prefill —
        // attestations are per-package, set only by attorney override
        DataResolver.ResolutionContext ctx = DataResolver.ResolutionContext.of(
                null, null, null, null, null,
                Map.of("signatory.lastName", "Smith"));

        ResolvedValue rv = dataResolver.resolve(q, ctx);
        assertThat(rv.hasValue()).isFalse();
    }

    @Test
    void nonReviewOnlyGenericQuestionStillResolves() {
        CanonicalQuestion q = registry.getAllQuestions().stream()
                .filter(cq -> cq.isGenericStorage() && !cq.isReviewOnly() && !cq.isList())
                .findFirst().orElseThrow();
        DataResolver.ResolutionContext ctx = DataResolver.ResolutionContext.of(
                null, null, null, null, null, Map.of(q.getKey(), "some-value"));

        ResolvedValue rv = dataResolver.resolve(q, ctx);
        assertThat(rv.hasValue()).isTrue();
        assertThat(rv.value()).isEqualTo("some-value");
    }
}
