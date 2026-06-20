package com.receipttracker.immigration.dto;

import java.time.LocalDateTime;
import java.util.List;

public record FormVersionDTO(
    Long id,
    String formType,
    String formTypeDisplayName,
    String editionDate,
    LocalDateTime downloadedAt,
    String pdfStorageKey,
    String status,
    Long approvedByUserId,
    LocalDateTime approvedAt,
    boolean fieldMappingVerified,
    List<String> pdfFieldNames,
    String releaseNotes,
    boolean hasProposedMapping,
    LocalDateTime createdAt,
    List<FormVersionAuditEventDTO> recentAudit
) {}
