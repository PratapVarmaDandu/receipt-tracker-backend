package com.receipttracker.immigration.dto;

public record CreateEventRequest(
        String eventType,   // EventType enum name
        String eventDate,   // ISO date yyyy-MM-dd
        String title,
        String description
) {}
