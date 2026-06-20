package com.receipttracker.immigration.dto;

import java.util.List;

public record ReviewSummaryDTO(
        Long packageId,
        String packageName,
        String status,
        int totalRequired,
        int totalAnswered,
        int completenessPercent,
        List<String> missingRequired,
        List<OwnerAnswerGroup> byOwner
) {
    public record OwnerAnswerGroup(
            String owner,
            int completenessPercent,
            List<AnswerSummary> answers
    ) {}

    public record AnswerSummary(
            String questionKey,
            String label,
            String type,
            boolean required,
            boolean hasValue,
            String source,
            boolean sensitive,
            boolean stale       // source=profile and verifiedAt null or >90 days old
    ) {}
}
