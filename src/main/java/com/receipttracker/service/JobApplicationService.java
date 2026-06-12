package com.receipttracker.service;

import com.receipttracker.dto.CreateInterviewRoundRequest;
import com.receipttracker.dto.CreateJobApplicationRequest;
import com.receipttracker.dto.InterviewRoundDTO;
import com.receipttracker.dto.JobApplicationDTO;
import com.receipttracker.model.*;
import com.receipttracker.repository.DocumentRepository;
import com.receipttracker.repository.InterviewRoundRepository;
import com.receipttracker.repository.JobApplicationRepository;
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
import java.util.*;
import java.util.stream.Collectors;

@Service
public class JobApplicationService {

    private static final Logger log = LoggerFactory.getLogger(JobApplicationService.class);

    @Autowired private JobApplicationRepository jobAppRepo;
    @Autowired private InterviewRoundRepository interviewRoundRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private DocumentRepository documentRepo;
    @Autowired private FeatureEntitlementService entitlement;

    // ── User resolution ─────────────────────────────────────────────────────

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        OAuth2User principal = (OAuth2User) auth.getPrincipal();
        String googleId = principal.getAttribute("sub");
        return userRepo.findByGoogleId(googleId)
                .orElseThrow(() -> new RuntimeException("Authenticated user not found"));
    }

    // ── CRUD ────────────────────────────────────────────────────────────────

    @Transactional
    public JobApplicationDTO create(CreateJobApplicationRequest req) {
        log.info(">>> create company={} title={}", req.getCompanyName(), req.getJobTitle());
        entitlement.requireFeature(AppFeature.JOB_TRACKER);
        User user = currentUser();

        JobApplication app = new JobApplication();
        app.setUser(user);
        mapFromRequest(req, app);
        if (app.getAppliedDate() == null) app.setAppliedDate(LocalDate.now());
        if (app.getStatus() == null)      app.setStatus(JobApplicationStatus.APPLIED);

        JobApplicationDTO dto = toDTO(jobAppRepo.save(app));
        log.info("<<< create id={}", dto.getId());
        return dto;
    }

    @Transactional(readOnly = true)
    public List<JobApplicationDTO> list(String statusStr) {
        entitlement.requireFeature(AppFeature.JOB_TRACKER);
        User user = currentUser();
        List<JobApplication> apps;
        if (statusStr != null && !statusStr.isBlank()) {
            apps = jobAppRepo.findByUserAndStatusOrderByAppliedDateDesc(
                    user, JobApplicationStatus.valueOf(statusStr.toUpperCase()));
        } else {
            apps = jobAppRepo.findByUserOrderByAppliedDateDesc(user);
        }
        return apps.stream().map(this::toDTO).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public JobApplicationDTO getById(Long id) {
        entitlement.requireFeature(AppFeature.JOB_TRACKER);
        User caller = currentUser();
        JobApplication app = jobAppRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Job application not found: " + id));
        if (!app.getUser().getId().equals(caller.getId())) throw new RuntimeException("Access denied");
        return toDTO(app);
    }

    @Transactional
    public JobApplicationDTO update(Long id, CreateJobApplicationRequest req) {
        log.info(">>> update id={}", id);
        entitlement.requireFeature(AppFeature.JOB_TRACKER);
        User caller = currentUser();
        JobApplication app = jobAppRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Job application not found: " + id));
        if (!app.getUser().getId().equals(caller.getId())) throw new RuntimeException("Access denied");
        mapFromRequest(req, app);
        JobApplicationDTO dto = toDTO(jobAppRepo.save(app));
        log.info("<<< update id={}", id);
        return dto;
    }

    @Transactional
    public void delete(Long id) {
        log.info(">>> delete id={}", id);
        entitlement.requireFeature(AppFeature.JOB_TRACKER);
        User caller = currentUser();
        JobApplication app = jobAppRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Job application not found: " + id));
        if (!app.getUser().getId().equals(caller.getId())) throw new RuntimeException("Access denied");
        jobAppRepo.delete(app);
        log.info("<<< delete id={}", id);
    }

    // ── Interview rounds ────────────────────────────────────────────────────

    @Transactional
    public InterviewRoundDTO addInterviewRound(Long jobAppId, CreateInterviewRoundRequest req) {
        entitlement.requireFeature(AppFeature.JOB_TRACKER);
        User caller = currentUser();
        JobApplication app = requireOwned(jobAppId, caller);

        InterviewRound round = new InterviewRound();
        round.setJobApplication(app);
        mapRoundFromRequest(req, round);
        return toRoundDTO(interviewRoundRepo.save(round));
    }

    @Transactional
    public InterviewRoundDTO updateInterviewRound(Long jobAppId, Long roundId, CreateInterviewRoundRequest req) {
        entitlement.requireFeature(AppFeature.JOB_TRACKER);
        User caller = currentUser();
        requireOwned(jobAppId, caller);
        InterviewRound round = interviewRoundRepo.findById(roundId)
                .orElseThrow(() -> new RuntimeException("Interview round not found: " + roundId));
        if (!round.getJobApplication().getId().equals(jobAppId))
            throw new RuntimeException("Round does not belong to this application");
        mapRoundFromRequest(req, round);
        return toRoundDTO(interviewRoundRepo.save(round));
    }

    @Transactional
    public void deleteInterviewRound(Long jobAppId, Long roundId) {
        entitlement.requireFeature(AppFeature.JOB_TRACKER);
        User caller = currentUser();
        requireOwned(jobAppId, caller);
        InterviewRound round = interviewRoundRepo.findById(roundId)
                .orElseThrow(() -> new RuntimeException("Interview round not found: " + roundId));
        if (!round.getJobApplication().getId().equals(jobAppId))
            throw new RuntimeException("Round does not belong to this application");
        interviewRoundRepo.delete(round);
    }

    // ── Summary ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> getSummary() {
        entitlement.requireFeature(AppFeature.JOB_TRACKER);
        User user = currentUser();
        List<JobApplication> all = jobAppRepo.findByUserOrderByAppliedDateDesc(user);
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDateTime now = LocalDateTime.now();

        Set<JobApplicationStatus> terminal = Set.of(
                JobApplicationStatus.REJECTED, JobApplicationStatus.WITHDRAWN, JobApplicationStatus.GHOSTED);

        long active    = all.stream().filter(a -> !terminal.contains(a.getStatus())).count();
        long thisMonth = all.stream().filter(a -> !a.getAppliedDate().isBefore(monthStart)).count();
        long followUpsDue = all.stream()
                .filter(a -> a.getFollowUpDate() != null
                        && !a.getFollowUpDate().isAfter(today)
                        && !terminal.contains(a.getStatus())
                        && a.getStatus() != JobApplicationStatus.OFFER)
                .count();
        long offers = all.stream().filter(a -> a.getStatus() == JobApplicationStatus.OFFER).count();
        long upcomingInterviews = all.stream()
                .flatMap(a -> a.getInterviewRounds().stream())
                .filter(r -> r.getScheduledAt() != null
                        && r.getScheduledAt().isAfter(now)
                        && r.getOutcome() == InterviewOutcome.PENDING)
                .count();

        Map<String, Long> byStatus = Arrays.stream(JobApplicationStatus.values())
                .collect(Collectors.toMap(
                        Enum::name,
                        s -> all.stream().filter(a -> a.getStatus() == s).count(),
                        (a, b) -> a,
                        LinkedHashMap::new));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", all.size());
        summary.put("active", active);
        summary.put("thisMonth", thisMonth);
        summary.put("followUpsDue", followUpsDue);
        summary.put("offersReceived", offers);
        summary.put("upcomingInterviews", upcomingInterviews);
        summary.put("byStatus", byStatus);
        return summary;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private JobApplication requireOwned(Long id, User caller) {
        JobApplication app = jobAppRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Job application not found: " + id));
        if (!app.getUser().getId().equals(caller.getId())) throw new RuntimeException("Access denied");
        return app;
    }

    private void mapFromRequest(CreateJobApplicationRequest req, JobApplication app) {
        if (req.getCompanyName() != null && !req.getCompanyName().isBlank())
            app.setCompanyName(req.getCompanyName().trim());
        if (req.getJobTitle() != null && !req.getJobTitle().isBlank())
            app.setJobTitle(req.getJobTitle().trim());
        app.setJobUrl(req.getJobUrl());
        app.setLocation(req.getLocation());
        app.setSalaryRange(req.getSalaryRange());
        if (req.getStatus() != null && !req.getStatus().isBlank())
            app.setStatus(JobApplicationStatus.valueOf(req.getStatus().toUpperCase()));
        if (req.getAppliedDate() != null) app.setAppliedDate(req.getAppliedDate());
        app.setFollowUpDate(req.getFollowUpDate());
        app.setHrName(req.getHrName());
        app.setHrEmail(req.getHrEmail());
        app.setHrPhone(req.getHrPhone());
        app.setRecruiterName(req.getRecruiterName());
        app.setRecruiterEmail(req.getRecruiterEmail());
        app.setJobDescription(req.getJobDescription());
        app.setPrepNotes(req.getPrepNotes());
        app.setNotes(req.getNotes());
        app.setResumeDocumentId(req.getResumeDocumentId());
        app.setResumeVersion(req.getResumeVersion());
    }

    private void mapRoundFromRequest(CreateInterviewRoundRequest req, InterviewRound round) {
        if (req.getRoundName() != null) round.setRoundName(req.getRoundName().trim());
        round.setScheduledAt(req.getScheduledAt());
        if (req.getFormat() != null && !req.getFormat().isBlank())
            round.setFormat(InterviewFormat.valueOf(req.getFormat().toUpperCase()));
        round.setInterviewerName(req.getInterviewerName());
        if (req.getOutcome() != null && !req.getOutcome().isBlank())
            round.setOutcome(InterviewOutcome.valueOf(req.getOutcome().toUpperCase()));
        else if (round.getOutcome() == null)
            round.setOutcome(InterviewOutcome.PENDING);
        if (req.getNotes() != null) round.setNotes(req.getNotes());
    }

    // ── DTO mapping ─────────────────────────────────────────────────────────

    JobApplicationDTO toDTO(JobApplication app) {
        JobApplicationDTO dto = new JobApplicationDTO();
        dto.setId(app.getId());
        dto.setCompanyName(app.getCompanyName());
        dto.setJobTitle(app.getJobTitle());
        dto.setJobUrl(app.getJobUrl());
        dto.setLocation(app.getLocation());
        dto.setSalaryRange(app.getSalaryRange());
        dto.setStatus(app.getStatus());
        dto.setAppliedDate(app.getAppliedDate());
        dto.setFollowUpDate(app.getFollowUpDate());
        dto.setHrName(app.getHrName());
        dto.setHrEmail(app.getHrEmail());
        dto.setHrPhone(app.getHrPhone());
        dto.setRecruiterName(app.getRecruiterName());
        dto.setRecruiterEmail(app.getRecruiterEmail());
        dto.setJobDescription(app.getJobDescription());
        dto.setPrepNotes(app.getPrepNotes());
        dto.setNotes(app.getNotes());
        dto.setResumeDocumentId(app.getResumeDocumentId());
        dto.setResumeVersion(app.getResumeVersion());
        dto.setCreatedAt(app.getCreatedAt());
        dto.setUpdatedAt(app.getUpdatedAt());

        if (app.getResumeDocumentId() != null) {
            documentRepo.findById(app.getResumeDocumentId())
                    .ifPresent(doc -> dto.setResumeDocumentTitle(doc.getTitle()));
        }

        LocalDateTime now = LocalDateTime.now();
        List<InterviewRoundDTO> rounds = app.getInterviewRounds().stream()
                .sorted(Comparator.comparing(r -> r.getScheduledAt() != null
                        ? r.getScheduledAt() : LocalDateTime.MAX))
                .map(this::toRoundDTO)
                .collect(Collectors.toList());
        dto.setInterviewRounds(rounds);

        app.getInterviewRounds().stream()
                .filter(r -> r.getScheduledAt() != null
                        && r.getScheduledAt().isAfter(now)
                        && r.getOutcome() == InterviewOutcome.PENDING)
                .min(Comparator.comparing(InterviewRound::getScheduledAt))
                .ifPresent(r -> dto.setNextInterviewAt(r.getScheduledAt()));

        Set<JobApplicationStatus> terminal = Set.of(
                JobApplicationStatus.REJECTED, JobApplicationStatus.WITHDRAWN,
                JobApplicationStatus.GHOSTED, JobApplicationStatus.OFFER);
        dto.setFollowUpDue(
                app.getFollowUpDate() != null
                && !app.getFollowUpDate().isAfter(LocalDate.now())
                && !terminal.contains(app.getStatus()));

        return dto;
    }

    private InterviewRoundDTO toRoundDTO(InterviewRound r) {
        InterviewRoundDTO dto = new InterviewRoundDTO();
        dto.setId(r.getId());
        dto.setJobApplicationId(r.getJobApplication().getId());
        dto.setRoundName(r.getRoundName());
        dto.setScheduledAt(r.getScheduledAt());
        dto.setFormat(r.getFormat());
        dto.setInterviewerName(r.getInterviewerName());
        dto.setOutcome(r.getOutcome());
        dto.setNotes(r.getNotes());
        dto.setCreatedAt(r.getCreatedAt());
        return dto;
    }
}
