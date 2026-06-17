package com.receipttracker.immigration.service;

import com.receipttracker.immigration.dto.CreateAppointmentRequest;
import com.receipttracker.immigration.dto.CreateEventRequest;
import com.receipttracker.immigration.dto.TimelineItemDTO;
import com.receipttracker.immigration.model.*;
import com.receipttracker.immigration.repository.AppointmentRepository;
import com.receipttracker.immigration.repository.CaseEventRepository;
import com.receipttracker.immigration.repository.ImmigrationCaseRepository;
import com.receipttracker.model.User;
import com.receipttracker.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class TimelineService {

    private static final Logger log = LoggerFactory.getLogger(TimelineService.class);

    @Autowired private CaseEventRepository eventRepo;
    @Autowired private AppointmentRepository appointmentRepo;
    @Autowired private ImmigrationCaseRepository caseRepo;
    @Autowired private PermissionService permissionService;
    @Autowired private UserRepository userRepo;

    // ── User resolution ──────────────────────────────────────────────────────

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        OAuth2User principal = (OAuth2User) auth.getPrincipal();
        String googleId = principal.getAttribute("sub");
        return userRepo.findByGoogleId(googleId)
                .orElseThrow(() -> new RuntimeException("Authenticated user not found"));
    }

    // ── Public API ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<TimelineItemDTO> getTimeline(Long caseId) {
        log.info(">>> getTimeline() caseId={}", caseId);
        User user = currentUser();
        permissionService.requireAccess(user, caseId, GrantScope.READ_CASE);

        ImmigrationCase c = caseRepo.findById(caseId)
                .orElseThrow(() -> new RuntimeException("Case not found: " + caseId));

        List<TimelineItemDTO> items = new ArrayList<>();

        eventRepo.findByImmigrationCaseOrderByEventDateDesc(c)
                .forEach(e -> items.add(eventToDTO(e)));

        appointmentRepo.findByImmigrationCaseOrderByScheduledAtDesc(c)
                .forEach(a -> items.add(appointmentToDTO(a)));

        // Unified chronological sort — most recent first
        items.sort(Comparator.comparing(TimelineItemDTO::date).reversed());
        return items;
    }

    @Transactional
    public TimelineItemDTO createEvent(Long caseId, CreateEventRequest req) {
        log.info(">>> createEvent() caseId={} type={}", caseId, req.eventType());
        User user = currentUser();
        permissionService.requireAccess(user, caseId, GrantScope.WRITE_CASE);

        ImmigrationCase c = caseRepo.findById(caseId)
                .orElseThrow(() -> new RuntimeException("Case not found: " + caseId));

        EventType eventType;
        try { eventType = EventType.valueOf(req.eventType()); }
        catch (IllegalArgumentException e) { throw new RuntimeException("Unknown event type: " + req.eventType()); }

        CaseEvent event = new CaseEvent();
        event.setImmigrationCase(c);
        event.setEventType(eventType);
        event.setEventDate(LocalDate.parse(req.eventDate()));
        event.setTitle(req.title());
        event.setDescription(req.description());
        event.setPerformedBy(user);
        event.setSystemGenerated(false);

        return eventToDTO(eventRepo.save(event));
    }

    @Transactional
    public void deleteEvent(Long caseId, Long eventId) {
        log.info(">>> deleteEvent() caseId={} eventId={}", caseId, eventId);
        User user = currentUser();
        permissionService.requireAccess(user, caseId, GrantScope.WRITE_CASE);
        CaseEvent e = eventRepo.findById(eventId)
                .orElseThrow(() -> new RuntimeException("Event not found: " + eventId));
        if (!e.getImmigrationCase().getId().equals(caseId)) {
            throw new RuntimeException("Event does not belong to case " + caseId);
        }
        eventRepo.delete(e);
    }

    @Transactional
    public TimelineItemDTO createAppointment(Long caseId, CreateAppointmentRequest req) {
        log.info(">>> createAppointment() caseId={} type={}", caseId, req.appointmentType());
        User user = currentUser();
        permissionService.requireAccess(user, caseId, GrantScope.WRITE_CASE);

        ImmigrationCase c = caseRepo.findById(caseId)
                .orElseThrow(() -> new RuntimeException("Case not found: " + caseId));

        AppointmentType type;
        try { type = AppointmentType.valueOf(req.appointmentType()); }
        catch (IllegalArgumentException e) { throw new RuntimeException("Unknown appointment type: " + req.appointmentType()); }

        Appointment appt = new Appointment();
        appt.setImmigrationCase(c);
        appt.setAppointmentType(type);
        appt.setScheduledAt(LocalDateTime.parse(req.scheduledAt()));
        appt.setLocation(req.location());
        appt.setNotes(req.notes());

        return appointmentToDTO(appointmentRepo.save(appt));
    }

    @Transactional
    public void deleteAppointment(Long caseId, Long apptId) {
        log.info(">>> deleteAppointment() caseId={} apptId={}", caseId, apptId);
        User user = currentUser();
        permissionService.requireAccess(user, caseId, GrantScope.WRITE_CASE);
        Appointment a = appointmentRepo.findById(apptId)
                .orElseThrow(() -> new RuntimeException("Appointment not found: " + apptId));
        if (!a.getImmigrationCase().getId().equals(caseId)) {
            throw new RuntimeException("Appointment does not belong to case " + caseId);
        }
        appointmentRepo.delete(a);
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    private TimelineItemDTO eventToDTO(CaseEvent e) {
        return new TimelineItemDTO(
                e.getId(),
                "EVENT",
                e.getEventType().name(),
                humanLabel(e.getEventType().name()),
                e.getTitle(),
                e.getDescription(),
                null,
                null,
                e.getEventDate().atStartOfDay(),
                e.isSystemGenerated(),
                e.getPerformedBy() != null ? e.getPerformedBy().getName() : null
        );
    }

    private TimelineItemDTO appointmentToDTO(Appointment a) {
        return new TimelineItemDTO(
                a.getId(),
                "APPOINTMENT",
                a.getAppointmentType().name(),
                humanLabel(a.getAppointmentType().name()),
                humanLabel(a.getAppointmentType().name()),
                a.getNotes(),
                a.getLocation(),
                a.getStatus().name(),
                a.getScheduledAt(),
                false,
                null
        );
    }

    private String humanLabel(String enumName) {
        StringBuilder sb = new StringBuilder();
        for (String word : enumName.split("_")) {
            if (word.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(word.charAt(0)));
            sb.append(word.substring(1).toLowerCase());
        }
        return sb.toString();
    }
}
