package com.receipttracker.immigration.dto;

import java.time.LocalDateTime;

/**
 * A questionnaire the current caller is responsible for, scoped to their own
 * relationship on the case (e.g. a beneficiary only ever sees their BENEFICIARY
 * questionnaire token). Used by the in-app "action needed" surface.
 */
public record MyQuestionnaireDTO(
        Long questionnaireId,
        Long packageId,
        String packageName,
        String targetRelationship,
        String token,
        String status,
        int questionCount,
        int answeredCount,
        LocalDateTime expiresAt
) {}
