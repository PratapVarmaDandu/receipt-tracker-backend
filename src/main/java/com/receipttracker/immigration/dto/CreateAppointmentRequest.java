package com.receipttracker.immigration.dto;

public record CreateAppointmentRequest(
        String appointmentType,  // AppointmentType enum name
        String scheduledAt,      // ISO datetime yyyy-MM-ddTHH:mm
        String location,
        String notes
) {}
