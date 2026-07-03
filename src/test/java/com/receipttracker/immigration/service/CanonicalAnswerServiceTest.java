package com.receipttracker.immigration.service;

import com.receipttracker.immigration.model.Beneficiary;
import com.receipttracker.immigration.model.CanonicalAnswer;
import com.receipttracker.immigration.model.ImmigrationCase;
import com.receipttracker.immigration.model.question.CanonicalQuestion;
import com.receipttracker.immigration.model.question.ResolvedValue;
import com.receipttracker.immigration.repository.CanonicalAnswerRepository;
import com.receipttracker.service.EncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CanonicalAnswerServiceTest {

    @Mock private CanonicalAnswerRepository answerRepo;
    @Mock private CanonicalQuestionRegistry questionRegistry;
    @Mock private EncryptionService encryptionService;

    @InjectMocks private CanonicalAnswerService service;

    private ImmigrationCase immCase;

    @BeforeEach
    void setUp() {
        Beneficiary ben = new Beneficiary();
        ben.setId(11L);

        immCase = new ImmigrationCase();
        immCase.setId(100L);
        immCase.setBeneficiary(ben);
        immCase.setEmployerImmOrgId(22L);
        immCase.setLawFirmImmOrgId(33L);
    }

    private CanonicalQuestion question(String key, String owner, String storage,
                                       String subjectScope, boolean encrypt) {
        CanonicalQuestion q = new CanonicalQuestion();
        q.setKey(key);
        q.setOwner(owner);
        q.setStorage(storage);
        q.setSubjectScope(subjectScope);
        q.setEncrypt(encrypt);
        return q;
    }

    // ── subjectFor ────────────────────────────────────────────────────────────

    @Test
    void subjectFor_caseScope_usesCaseId() {
        CanonicalQuestion q = question("job.socCode", "EMPLOYER", "generic", "CASE", false);
        var ref = service.subjectFor(q, immCase);
        assertThat(ref).isPresent();
        assertThat(ref.get().subjectType()).isEqualTo(CanonicalAnswer.SUBJECT_CASE);
        assertThat(ref.get().subjectId()).isEqualTo(100L);
    }

    @Test
    void subjectFor_defaultsFromOwner_whenScopeAbsent() {
        assertThat(service.subjectFor(question("k1", "BENEFICIARY", "generic", null, false), immCase))
                .contains(new CanonicalAnswerService.SubjectRef(CanonicalAnswer.SUBJECT_BENEFICIARY, 11L));
        assertThat(service.subjectFor(question("k2", "EMPLOYER", "generic", null, false), immCase))
                .contains(new CanonicalAnswerService.SubjectRef(CanonicalAnswer.SUBJECT_ORG, 22L));
        assertThat(service.subjectFor(question("k3", "ATTORNEY", "generic", null, false), immCase))
                .contains(new CanonicalAnswerService.SubjectRef(CanonicalAnswer.SUBJECT_ORG, 33L));
    }

    @Test
    void subjectFor_missingParty_returnsEmpty() {
        immCase.setEmployerImmOrgId(null);
        CanonicalQuestion q = question("employer.x", "EMPLOYER", "generic", null, false);
        assertThat(service.subjectFor(q, immCase)).isEmpty();
    }

    // ── save ──────────────────────────────────────────────────────────────────

    @Test
    void save_nonSensitive_storesPlaintext_noHash() {
        CanonicalQuestion q = question("job.socCode", "EMPLOYER", "generic", "CASE", false);
        when(answerRepo.findBySubjectTypeAndSubjectIdAndQuestionKey(any(), any(), any()))
                .thenReturn(Optional.empty());

        service.save(new CanonicalAnswerService.SubjectRef("CASE", 100L), q, "15-1252", 7L);

        verify(answerRepo).save(argThat(a ->
                "15-1252".equals(a.getValueJson())
                && a.getValueHash() == null
                && "CASE".equals(a.getSubjectType())
                && a.getSubjectId().equals(100L)
                && a.getUpdatedByUserId().equals(7L)));
        verify(encryptionService, never()).encrypt(any());
    }

    @Test
    void save_sensitive_encryptsAndHashes() {
        CanonicalQuestion q = question("beneficiary.secret", "BENEFICIARY", "generic", null, true);
        when(answerRepo.findBySubjectTypeAndSubjectIdAndQuestionKey(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(encryptionService.encrypt("raw-value")).thenReturn("ENC(raw-value)");

        service.save(new CanonicalAnswerService.SubjectRef("BENEFICIARY", 11L), q, "raw-value", 7L);

        verify(answerRepo).save(argThat(a ->
                "ENC(raw-value)".equals(a.getValueJson())
                && a.getValueHash() != null && a.getValueHash().length() == 64));
    }

    @Test
    void save_blankValue_isIgnored() {
        CanonicalQuestion q = question("job.socCode", "EMPLOYER", "generic", "CASE", false);
        service.save(new CanonicalAnswerService.SubjectRef("CASE", 100L), q, "  ", 7L);
        verify(answerRepo, never()).save(any());
    }

    @Test
    void save_existingRow_isUpdatedNotDuplicated() {
        CanonicalQuestion q = question("job.socCode", "EMPLOYER", "generic", "CASE", false);
        CanonicalAnswer existing = new CanonicalAnswer();
        existing.setId(5L);
        existing.setSubjectType("CASE");
        existing.setSubjectId(100L);
        existing.setQuestionKey("job.socCode");
        existing.setValueJson("old");
        when(answerRepo.findBySubjectTypeAndSubjectIdAndQuestionKey("CASE", 100L, "job.socCode"))
                .thenReturn(Optional.of(existing));

        service.save(new CanonicalAnswerService.SubjectRef("CASE", 100L), q, "new", 7L);

        verify(answerRepo).save(argThat(a -> a.getId().equals(5L) && "new".equals(a.getValueJson())));
    }

    // ── loadForCase ───────────────────────────────────────────────────────────

    @Test
    void loadForCase_returnsPlaintextForGenericQuestions() {
        CanonicalQuestion soc = question("job.socCode", "EMPLOYER", "generic", "CASE", false);
        CanonicalQuestion typed = question("beneficiary.firstName", "BENEFICIARY", null, null, false);
        when(questionRegistry.findByKey("job.socCode")).thenReturn(Optional.of(soc));

        CanonicalAnswer row = new CanonicalAnswer();
        row.setSubjectType("CASE");
        row.setSubjectId(100L);
        row.setQuestionKey("job.socCode");
        row.setValueJson("15-1252");
        when(answerRepo.findBySubjectTypeAndSubjectId("CASE", 100L)).thenReturn(List.of(row));

        Map<String, String> result = service.loadForCase(immCase, List.of(soc, typed));

        assertThat(result).containsExactly(entry("job.socCode", "15-1252"));
        // typed questions never touch the store
        verify(answerRepo, never()).findBySubjectTypeAndSubjectId(eq("BENEFICIARY"), any());
    }

    @Test
    void loadForCase_decryptsSensitiveValues() {
        CanonicalQuestion q = question("beneficiary.secret", "BENEFICIARY", "generic", null, true);
        when(questionRegistry.findByKey("beneficiary.secret")).thenReturn(Optional.of(q));
        when(encryptionService.decrypt("ENC(x)")).thenReturn("x");

        CanonicalAnswer row = new CanonicalAnswer();
        row.setSubjectType("BENEFICIARY");
        row.setSubjectId(11L);
        row.setQuestionKey("beneficiary.secret");
        row.setValueJson("ENC(x)");
        when(answerRepo.findBySubjectTypeAndSubjectId("BENEFICIARY", 11L)).thenReturn(List.of(row));

        Map<String, String> result = service.loadForCase(immCase, List.of(q));

        assertThat(result).containsExactly(entry("beneficiary.secret", "x"));
    }

    @Test
    void loadForCase_decryptFailure_isSkippedNonFatal() {
        CanonicalQuestion q = question("beneficiary.secret", "BENEFICIARY", "generic", null, true);
        when(questionRegistry.findByKey("beneficiary.secret")).thenReturn(Optional.of(q));
        when(encryptionService.decrypt(any())).thenThrow(new RuntimeException("bad key"));

        CanonicalAnswer row = new CanonicalAnswer();
        row.setSubjectType("BENEFICIARY");
        row.setSubjectId(11L);
        row.setQuestionKey("beneficiary.secret");
        row.setValueJson("ENC(x)");
        when(answerRepo.findBySubjectTypeAndSubjectId("BENEFICIARY", 11L)).thenReturn(List.of(row));

        assertThat(service.loadForCase(immCase, List.of(q))).isEmpty();
    }

    // ── DataResolver integration (generic lookup path) ───────────────────────

    @Test
    void dataResolver_resolvesGenericQuestionFromContextMap() {
        DataResolver resolver = new DataResolver();
        CanonicalQuestion q = question("job.socCode", "EMPLOYER", "generic", "CASE", false);

        DataResolver.ResolutionContext ctx = DataResolver.ResolutionContext.of(
                null, null, null, immCase, null, Map.of("job.socCode", "15-1252"));

        ResolvedValue rv = resolver.resolve(q, ctx);
        assertThat(rv.hasValue()).isTrue();
        assertThat(rv.value()).isEqualTo("15-1252");
        assertThat(rv.source()).isEqualTo("store");
    }

    @Test
    void dataResolver_genericQuestionWithoutStoredValue_resolvesNone() {
        DataResolver resolver = new DataResolver();
        CanonicalQuestion q = question("job.salaryAmount", "EMPLOYER", "generic", "CASE", false);

        DataResolver.ResolutionContext ctx = DataResolver.ResolutionContext.of(
                null, null, null, immCase, null);

        assertThat(resolver.resolve(q, ctx).hasValue()).isFalse();
    }
}
