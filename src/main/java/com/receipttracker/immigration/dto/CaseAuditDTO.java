package com.receipttracker.immigration.dto;

import java.util.List;

/**
 * Grouped audit response for GET /api/immigration/cases/{id}/audit.
 * caseEvents     — ImmAuditEvent rows (status transitions, assignments, etc.)
 * dataChanges    — ImmFieldAuditEvent rows for CHANGED / SCANNED / QUESTIONNAIRE_SUBMITTED
 * formVersionEvents — FormVersionAuditEvent rows for form types used in this case's packages
 * pdfEvents      — ImmFieldAuditEvent rows for PDF_GENERATED / ATTORNEY_APPROVED
 */
public record CaseAuditDTO(
        List<ActivityFeedItemDTO> caseEvents,
        List<ImmFieldAuditEventDTO> dataChanges,
        List<FormVersionAuditEventDTO> formVersionEvents,
        List<ImmFieldAuditEventDTO> pdfEvents
) {}
