package com.receipttracker.immigration.service;

import com.receipttracker.immigration.model.CanonicalAnswer;
import com.receipttracker.immigration.model.ImmigrationCase;
import com.receipttracker.immigration.model.question.CanonicalQuestion;
import com.receipttracker.immigration.repository.CanonicalAnswerRepository;
import com.receipttracker.service.EncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Persistence for generic-storage canonical answers (imm_canonical_answers).
 *
 * Questions with storage="generic" in canonical-questions.json have no typed
 * column — their values live here, keyed by (subjectType, subjectId, questionKey).
 * Sensitive (encrypt=true) values are AES-256-GCM encrypted with a SHA-256
 * plaintext hash for comparison without decryption; all values returned by
 * load methods are plaintext.
 */
@Service
public class CanonicalAnswerService {

    private static final Logger log = LoggerFactory.getLogger(CanonicalAnswerService.class);

    @Autowired private CanonicalAnswerRepository answerRepo;
    @Autowired private CanonicalQuestionRegistry questionRegistry;
    @Autowired private EncryptionService encryptionService;

    /** Where a generic answer lives: ("BENEFICIARY"|"ORG"|"CASE", id). */
    public record SubjectRef(String subjectType, Long subjectId) {}

    // ── Subject resolution ────────────────────────────────────────────────────

    /**
     * Maps a question's subjectScope (or its owner, when subjectScope is absent)
     * to the concrete storage subject for the given case. Empty when the case
     * lacks the referenced party (e.g. no employer org yet).
     */
    public Optional<SubjectRef> subjectFor(CanonicalQuestion q, ImmigrationCase immCase) {
        if (q == null || immCase == null) return Optional.empty();
        String scope = q.getSubjectScope() != null ? q.getSubjectScope() : defaultScope(q.getOwner());
        if (scope == null) return Optional.empty();
        return switch (scope) {
            case "BENEFICIARY" -> immCase.getBeneficiary() != null
                    ? Optional.of(new SubjectRef(CanonicalAnswer.SUBJECT_BENEFICIARY, immCase.getBeneficiary().getId()))
                    : Optional.empty();
            case "EMPLOYER_ORG" -> immCase.getEmployerImmOrgId() != null
                    ? Optional.of(new SubjectRef(CanonicalAnswer.SUBJECT_ORG, immCase.getEmployerImmOrgId()))
                    : Optional.empty();
            case "LAW_FIRM_ORG" -> immCase.getLawFirmImmOrgId() != null
                    ? Optional.of(new SubjectRef(CanonicalAnswer.SUBJECT_ORG, immCase.getLawFirmImmOrgId()))
                    : Optional.empty();
            case "CASE" -> Optional.of(new SubjectRef(CanonicalAnswer.SUBJECT_CASE, immCase.getId()));
            default -> {
                log.warn("Unknown subjectScope '{}' on question '{}'", scope, q.getKey());
                yield Optional.empty();
            }
        };
    }

    private static String defaultScope(String owner) {
        if (owner == null) return null;
        return switch (owner) {
            case "BENEFICIARY" -> "BENEFICIARY";
            case "EMPLOYER"    -> "EMPLOYER_ORG";
            case "ATTORNEY"    -> "LAW_FIRM_ORG";
            default -> null;
        };
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    /**
     * Loads plaintext values for every generic-storage question relevant to this
     * case, grouped into one map keyed by questionKey. One query per distinct
     * subject. Decrypt failures are skipped (non-fatal).
     */
    @Transactional(readOnly = true)
    public Map<String, String> loadForCase(ImmigrationCase immCase, List<CanonicalQuestion> questions) {
        Map<String, String> result = new LinkedHashMap<>();
        if (immCase == null || questions == null) return result;

        // Group the generic questions by their storage subject
        Map<SubjectRef, Set<String>> keysBySubject = new LinkedHashMap<>();
        for (CanonicalQuestion q : questions) {
            if (!q.isGenericStorage()) continue;
            subjectFor(q, immCase).ifPresent(ref ->
                    keysBySubject.computeIfAbsent(ref, r -> new HashSet<>()).add(q.getKey()));
        }

        for (Map.Entry<SubjectRef, Set<String>> e : keysBySubject.entrySet()) {
            for (CanonicalAnswer ans : answerRepo.findBySubjectTypeAndSubjectId(
                    e.getKey().subjectType(), e.getKey().subjectId())) {
                if (!e.getValue().contains(ans.getQuestionKey())) continue;
                String plain = toPlaintext(ans);
                if (plain != null && !plain.isBlank()) result.put(ans.getQuestionKey(), plain);
            }
        }
        return result;
    }

    private String toPlaintext(CanonicalAnswer ans) {
        boolean sensitive = questionRegistry.findByKey(ans.getQuestionKey())
                .map(CanonicalQuestion::isEncrypt).orElse(false);
        if (!sensitive) return ans.getValueJson();
        try {
            return encryptionService.decrypt(ans.getValueJson());
        } catch (Exception e) {
            log.warn("Decrypt failed for canonical answer '{}': {}", ans.getQuestionKey(), e.getMessage());
            return null;
        }
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    /**
     * Upserts one generic answer. Encrypts when the question is sensitive.
     * Blank values are ignored (never clears an existing answer).
     */
    @Transactional
    public void save(SubjectRef ref, CanonicalQuestion question, String plaintext, Long userId) {
        if (ref == null || question == null || plaintext == null || plaintext.isBlank()) return;

        CanonicalAnswer ans = answerRepo
                .findBySubjectTypeAndSubjectIdAndQuestionKey(ref.subjectType(), ref.subjectId(), question.getKey())
                .orElseGet(() -> {
                    CanonicalAnswer a = new CanonicalAnswer();
                    a.setSubjectType(ref.subjectType());
                    a.setSubjectId(ref.subjectId());
                    a.setQuestionKey(question.getKey());
                    return a;
                });

        if (question.isEncrypt()) {
            ans.setValueJson(encryptionService.encrypt(plaintext));
            ans.setValueHash(sha256(plaintext));
        } else {
            ans.setValueJson(plaintext);
            ans.setValueHash(null);
        }
        ans.setUpdatedByUserId(userId);
        answerRepo.save(ans);
    }

    private static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
