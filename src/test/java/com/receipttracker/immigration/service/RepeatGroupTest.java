package com.receipttracker.immigration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.receipttracker.immigration.model.CanonicalProfile;
import com.receipttracker.immigration.model.question.CanonicalQuestion;
import com.receipttracker.immigration.model.question.FormFieldEntry;
import com.receipttracker.immigration.model.question.RepeatGroupSpec;
import com.receipttracker.immigration.model.question.RepeatItemField;
import com.receipttracker.immigration.model.question.ResolvedValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Phase 4 repeat groups: registry validation, DataResolver LIST prefill,
 * submit sanitization, and PDF fill row extraction.
 */
class RepeatGroupTest {

    private CanonicalQuestionRegistry registry;
    private DataResolver dataResolver;
    private FilingPackageService packageService;
    private ImmPdfGenerationService pdfService;
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

        packageService = new FilingPackageService();
        ReflectionTestUtils.setField(packageService, "objectMapper", objectMapper);

        pdfService = new ImmPdfGenerationService();
        ReflectionTestUtils.setField(pdfService, "objectMapper", objectMapper);
    }

    // ── Config loading ────────────────────────────────────────────────────────

    @Test
    void listQuestionsLoadFromConfig() {
        CanonicalQuestion aliases = registry.findByKey("beneficiary.aliases").orElseThrow();
        assertThat(aliases.isList()).isTrue();
        assertThat(aliases.isGenericStorage()).isTrue();
        assertThat(aliases.getRepeatGroup()).isNotNull();
        assertThat(aliases.getRepeatGroup().getMaxRows()).isEqualTo(3);
        assertThat(aliases.getRepeatGroup().getItemFields())
                .extracting(RepeatItemField::getKey)
                .containsExactly("lastName", "firstName", "middleName");

        CanonicalQuestion stays = registry.findByKey("beneficiary.priorStays").orElseThrow();
        assertThat(stays.getRepeatGroup().getSourceList()).isEqualTo("priorVisasJson");
    }

    // ── Mapping-entry validation (registry.repeatEntryProblem) ────────────────

    @Test
    void validRepeatEntryHasNoProblem() {
        FormFieldEntry e = repeatEntry("beneficiary.aliases", 0, "lastName");
        assertThat(registry.repeatEntryProblem(e)).isNull();
    }

    @Test
    void scalarEntryOnScalarQuestionHasNoProblem() {
        FormFieldEntry e = new FormFieldEntry();
        e.setQuestionKey("beneficiary.lastName");
        e.setPdfFieldName("Pt3Line1_FamilyName");
        assertThat(registry.repeatEntryProblem(e)).isNull();
    }

    @Test
    void listQuestionWithoutRepeatIndexIsAProblem() {
        FormFieldEntry e = new FormFieldEntry();
        e.setQuestionKey("beneficiary.aliases");
        e.setPdfFieldName("SomeField");
        assertThat(registry.repeatEntryProblem(e)).contains("needs repeatIndex");
    }

    @Test
    void repeatIndexWithoutItemFieldIsAProblem() {
        FormFieldEntry e = new FormFieldEntry();
        e.setQuestionKey("beneficiary.aliases");
        e.setRepeatIndex(0);
        assertThat(registry.repeatEntryProblem(e)).contains("must be set together");
    }

    @Test
    void repeatEntryOnNonListQuestionIsAProblem() {
        FormFieldEntry e = repeatEntry("beneficiary.lastName", 0, "lastName");
        assertThat(registry.repeatEntryProblem(e)).contains("not a LIST question");
    }

    @Test
    void repeatIndexBeyondMaxRowsIsAProblem() {
        FormFieldEntry e = repeatEntry("beneficiary.aliases", 5, "lastName");
        assertThat(registry.repeatEntryProblem(e)).contains("out of range");
    }

    @Test
    void unknownItemFieldIsAProblem() {
        FormFieldEntry e = repeatEntry("beneficiary.aliases", 0, "nickname");
        assertThat(registry.repeatEntryProblem(e)).contains("not declared");
    }

    // ── DataResolver LIST prefill ─────────────────────────────────────────────

    @Test
    void genericStoreValueWinsOverSourceList() {
        CanonicalQuestion stays = registry.findByKey("beneficiary.priorStays").orElseThrow();
        CanonicalProfile profile = new CanonicalProfile();
        profile.setPriorVisasJson("[{\"visaType\":\"F-1\",\"country\":\"USA\"}]");
        String stored = "[{\"visaType\":\"H-1B\",\"country\":\"USA\"}]";

        DataResolver.ResolutionContext ctx = DataResolver.ResolutionContext.of(
                profile, null, null, null, null, Map.of("beneficiary.priorStays", stored));

        ResolvedValue rv = dataResolver.resolve(stays, ctx);
        assertThat(rv.value()).isEqualTo(stored);
        assertThat(rv.source()).isEqualTo("store");
    }

    @Test
    void sourceListProjectionKeepsOnlyDeclaredItemFields() {
        CanonicalQuestion stays = registry.findByKey("beneficiary.priorStays").orElseThrow();
        CanonicalProfile profile = new CanonicalProfile();
        profile.setPriorVisasJson(
                "[{\"visaType\":\"F-1\",\"country\":\"USA\",\"issueDate\":\"2019-08-01\","
                + "\"documentIds\":[4,5],\"id\":\"abc\"}]");

        DataResolver.ResolutionContext ctx = DataResolver.ResolutionContext.of(
                profile, null, null, null, null);

        ResolvedValue rv = dataResolver.resolve(stays, ctx);
        assertThat(rv.hasValue()).isTrue();
        assertThat(rv.source()).isEqualTo("profile");
        assertThat(rv.value()).contains("F-1").contains("2019-08-01")
                .doesNotContain("documentIds").doesNotContain("abc");
    }

    @Test
    void emptyStoredArrayFallsBackToSourceList() {
        CanonicalQuestion stays = registry.findByKey("beneficiary.priorStays").orElseThrow();
        CanonicalProfile profile = new CanonicalProfile();
        profile.setPriorVisasJson("[{\"visaType\":\"F-1\"}]");

        DataResolver.ResolutionContext ctx = DataResolver.ResolutionContext.of(
                profile, null, null, null, null, Map.of("beneficiary.priorStays", "[]"));

        ResolvedValue rv = dataResolver.resolve(stays, ctx);
        assertThat(rv.source()).isEqualTo("profile");
        assertThat(rv.value()).contains("F-1");
    }

    @Test
    void noProfileAndNoStoreResolvesToNone() {
        CanonicalQuestion stays = registry.findByKey("beneficiary.priorStays").orElseThrow();
        DataResolver.ResolutionContext ctx = DataResolver.ResolutionContext.of(
                null, null, null, null, null);
        assertThat(dataResolver.resolve(stays, ctx).hasValue()).isFalse();
    }

    @Test
    void aliasesWithoutSourceListUseGenericStoreOnly() {
        CanonicalQuestion aliases = registry.findByKey("beneficiary.aliases").orElseThrow();
        CanonicalProfile profile = new CanonicalProfile();
        DataResolver.ResolutionContext ctx = DataResolver.ResolutionContext.of(
                profile, null, null, null, null, Map.of());
        assertThat(dataResolver.resolve(aliases, ctx).hasValue()).isFalse();
    }

    // ── Submit sanitization ───────────────────────────────────────────────────

    @Test
    void sanitizeRejectsNonArrayJson() {
        CanonicalQuestion q = listQuestion(2);
        assertThatThrownBy(() -> packageService.sanitizeListAnswer(q, "not json"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> packageService.sanitizeListAnswer(q, "{\"a\":1}"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void sanitizeStripsUnknownKeysAndBlankRows() {
        CanonicalQuestion q = listQuestion(4);
        String out = packageService.sanitizeListAnswer(q,
                "[{\"lastName\":\" Doe \",\"hacker\":\"x\"},{\"lastName\":\"  \"},{}]");
        assertThat(out).isEqualTo("[{\"lastName\":\"Doe\"}]");
    }

    @Test
    void sanitizeTruncatesRowsBeyondMaxRows() {
        CanonicalQuestion q = listQuestion(2);
        String out = packageService.sanitizeListAnswer(q,
                "[{\"lastName\":\"A\"},{\"lastName\":\"B\"},{\"lastName\":\"C\"}]");
        assertThat(out).isEqualTo("[{\"lastName\":\"A\"},{\"lastName\":\"B\"}]");
    }

    @Test
    void sanitizeReturnsNullWhenNothingSurvives() {
        CanonicalQuestion q = listQuestion(2);
        assertThat(packageService.sanitizeListAnswer(q, "[]")).isNull();
        assertThat(packageService.sanitizeListAnswer(q, "[{\"unknown\":\"x\"}]")).isNull();
    }

    // ── PDF fill row extraction ───────────────────────────────────────────────

    @Test
    void extractListCellReadsRowAndField() {
        CanonicalQuestion q = listQuestion(2);
        FormFieldEntry e = repeatEntry("beneficiary.aliases", 1, "lastName");
        String cell = pdfService.extractListCell(q, e,
                "[{\"lastName\":\"Doe\"},{\"lastName\":\"Roe\"}]", new HashMap<>(), new HashSet<>());
        assertThat(cell).isEqualTo("Roe");
    }

    @Test
    void extractListCellIndexBeyondRowsIsNull() {
        CanonicalQuestion q = listQuestion(4);
        FormFieldEntry e = repeatEntry("beneficiary.aliases", 3, "lastName");
        String cell = pdfService.extractListCell(q, e,
                "[{\"lastName\":\"Doe\"}]", new HashMap<>(), new HashSet<>());
        assertThat(cell).isNull();
    }

    @Test
    void extractListCellIgnoresRowsBeyondMaxRows() {
        CanonicalQuestion q = listQuestion(1);
        FormFieldEntry e = repeatEntry("beneficiary.aliases", 1, "lastName");
        String cell = pdfService.extractListCell(q, e,
                "[{\"lastName\":\"Doe\"},{\"lastName\":\"Roe\"}]", new HashMap<>(), new HashSet<>());
        assertThat(cell).isNull();
    }

    @Test
    void extractListCellNonArrayValueIsNull() {
        CanonicalQuestion q = listQuestion(2);
        FormFieldEntry e = repeatEntry("beneficiary.aliases", 0, "lastName");
        String cell = pdfService.extractListCell(q, e, "oops", new HashMap<>(), new HashSet<>());
        assertThat(cell).isNull();
    }

    @Test
    void extractListCellMissingFieldIsNull() {
        CanonicalQuestion q = listQuestion(2);
        FormFieldEntry e = repeatEntry("beneficiary.aliases", 0, "middleName");
        String cell = pdfService.extractListCell(q, e,
                "[{\"lastName\":\"Doe\"}]", new HashMap<>(), new HashSet<>());
        assertThat(cell).isNull();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static FormFieldEntry repeatEntry(String questionKey, int index, String itemField) {
        FormFieldEntry e = new FormFieldEntry();
        e.setQuestionKey(questionKey);
        e.setPdfFieldName("Field_" + index + "_" + itemField);
        e.setRepeatIndex(index);
        e.setItemField(itemField);
        return e;
    }

    /** A LIST question with itemFields lastName/firstName/middleName. */
    private static CanonicalQuestion listQuestion(int maxRows) {
        CanonicalQuestion q = new CanonicalQuestion();
        q.setKey("beneficiary.aliases");
        q.setType("LIST");
        q.setStorage("generic");
        RepeatGroupSpec spec = new RepeatGroupSpec();
        spec.setMaxRows(maxRows);
        spec.setItemFields(List.of(
                itemField("lastName"), itemField("firstName"), itemField("middleName")));
        q.setRepeatGroup(spec);
        return q;
    }

    private static RepeatItemField itemField(String key) {
        RepeatItemField f = new RepeatItemField();
        f.setKey(key);
        f.setType("TEXT");
        return f;
    }
}
