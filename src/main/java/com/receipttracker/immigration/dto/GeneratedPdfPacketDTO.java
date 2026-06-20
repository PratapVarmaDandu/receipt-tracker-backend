package com.receipttracker.immigration.dto;

import java.time.LocalDateTime;
import java.util.List;

public record GeneratedPdfPacketDTO(
        Long id,
        Long packageId,
        Long caseId,
        List<FormVersionUsedDTO> formVersionsUsed,
        LocalDateTime generatedAt,
        Long generatedByUserId,
        String status,
        LocalDateTime attorneyApprovedAt,
        Long attorneyApprovedBy,
        String pdfStorageKey,
        List<GenerationAuditEntryDTO> generationAudit,
        LocalDateTime createdAt
) {
    public record FormVersionUsedDTO(String formType, Long versionId, String editionDate) {}
    public record GenerationAuditEntryDTO(
            String questionKey, String pdfField, String source,
            Long versionId, boolean filled, String formType
    ) {}
}
