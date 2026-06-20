package com.receipttracker.immigration.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record QuestionnairePublicDTO(
        Long id,
        Long packageId,
        String packageName,
        String targetRelationship,
        String status,
        LocalDateTime expiresAt,
        LocalDateTime submittedAt,
        List<String> formTypes,
        List<QuestionnaireSection> sections
) {

    public record QuestionnaireSection(
            String sectionId,
            String sectionLabel,
            List<QuestionnaireQuestion> questions
    ) {}

    public record QuestionnaireQuestion(
            String key,
            String label,
            String sublabel,
            String type,         // TEXT | TEXT_SENSITIVE | DATE | NUMBER | BOOLEAN | SELECT | TEXTAREA
            boolean required,
            Map<String, Object> validation,
            List<String> options,          // non-null for SELECT type
            String prefillValue,           // null for TEXT_SENSITIVE or no prefill
            String prefillSource           // "profile" | "org" | "questionnaire" | "none"
    ) {}
}
