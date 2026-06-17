package com.receipttracker.immigration.dto;

import java.time.LocalDateTime;

/**
 * Unified view of a CaseEvent or Appointment for the timeline feed.
 * itemType is "EVENT" or "APPOINTMENT".
 * date is the ISO-8601 timestamp used for chronological sorting (event date at midnight, appointment scheduledAt).
 */
public record TimelineItemDTO(
        Long id,
        String itemType,      // "EVENT" | "APPOINTMENT"
        String eventType,     // EventType or AppointmentType name
        String typeLabel,
        String title,
        String description,
        String location,      // appointments only
        String status,
        LocalDateTime date,
        boolean systemGenerated,
        String performedByName
) {}
