package com.receipttracker.immigration.controller;

import com.receipttracker.immigration.dto.CreateAppointmentRequest;
import com.receipttracker.immigration.dto.CreateEventRequest;
import com.receipttracker.immigration.dto.TimelineItemDTO;
import com.receipttracker.immigration.service.TimelineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/immigration/cases/{caseId}/timeline")
public class TimelineController {

    private static final Logger log = LoggerFactory.getLogger(TimelineController.class);

    @Autowired private TimelineService timelineService;

    @GetMapping
    public ResponseEntity<?> getTimeline(@PathVariable Long caseId) {
        log.info("GET /api/immigration/cases/{}/timeline", caseId);
        try {
            List<TimelineItemDTO> items = timelineService.getTimeline(caseId);
            return ResponseEntity.ok(items);
        } catch (RuntimeException e) { return handleError(e); }
    }

    @PostMapping("/events")
    public ResponseEntity<?> createEvent(@PathVariable Long caseId, @RequestBody CreateEventRequest req) {
        log.info("POST /api/immigration/cases/{}/timeline/events type={}", caseId, req.eventType());
        try {
            TimelineItemDTO item = timelineService.createEvent(caseId, req);
            return ResponseEntity.status(HttpStatus.CREATED).body(item);
        } catch (RuntimeException e) { return handleError(e); }
    }

    @DeleteMapping("/events/{eventId}")
    public ResponseEntity<?> deleteEvent(@PathVariable Long caseId, @PathVariable Long eventId) {
        log.info("DELETE /api/immigration/cases/{}/timeline/events/{}", caseId, eventId);
        try {
            timelineService.deleteEvent(caseId, eventId);
            return ResponseEntity.noContent().build();
        } catch (RuntimeException e) { return handleError(e); }
    }

    @PostMapping("/appointments")
    public ResponseEntity<?> createAppointment(@PathVariable Long caseId, @RequestBody CreateAppointmentRequest req) {
        log.info("POST /api/immigration/cases/{}/timeline/appointments type={}", caseId, req.appointmentType());
        try {
            TimelineItemDTO item = timelineService.createAppointment(caseId, req);
            return ResponseEntity.status(HttpStatus.CREATED).body(item);
        } catch (RuntimeException e) { return handleError(e); }
    }

    @DeleteMapping("/appointments/{apptId}")
    public ResponseEntity<?> deleteAppointment(@PathVariable Long caseId, @PathVariable Long apptId) {
        log.info("DELETE /api/immigration/cases/{}/timeline/appointments/{}", caseId, apptId);
        try {
            timelineService.deleteAppointment(caseId, apptId);
            return ResponseEntity.noContent().build();
        } catch (RuntimeException e) { return handleError(e); }
    }

    private ResponseEntity<?> handleError(RuntimeException e) {
        String msg = e.getMessage();
        if (msg != null && msg.startsWith("Access denied")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", msg));
        }
        log.error("!!! TimelineController error: {}", msg);
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }
}
