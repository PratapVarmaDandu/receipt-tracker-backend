package com.receipttracker.immigration.service;

import com.receipttracker.immigration.dto.UscisStatusDTO;
import com.receipttracker.immigration.model.*;
import com.receipttracker.immigration.repository.*;
import com.receipttracker.model.User;
import com.receipttracker.repository.UserRepository;
import com.receipttracker.service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Polls USCIS eGov portal for receipt number status changes.
 * HTML parsing uses a heuristic h4/p pattern that may need updating if USCIS changes their layout.
 */
@Service
public class UscisPollingService {

    private static final Logger log = LoggerFactory.getLogger(UscisPollingService.class);

    private static final String USCIS_URL = "https://egov.uscis.gov/casestatus/landing.do";
    // Matches "Case Was Received", "Case Is Being Actively Reviewed By USCIS", etc.
    private static final Pattern STATUS_PATTERN =
            Pattern.compile("(?i)<h4[^>]*>\\s*(Case\\s[^<]{3,200})\\s*</h4>");

    @Value("${app.frontend.url}")
    private String frontendUrl;

    @Autowired private ImmigrationCaseRepository caseRepo;
    @Autowired private UscisPollResultRepository pollRepo;
    @Autowired private CaseEventRepository eventRepo;
    @Autowired private ImmOrgMemberRepository memberRepo;
    @Autowired private PermissionService permissionService;
    @Autowired private UserRepository userRepo;
    @Autowired private EmailService emailService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    // ── Scheduled job ────────────────────────────────────────────────────────

    @Scheduled(cron = "0 0 9 * * *")
    @Transactional
    public void runDailyPoll() {
        log.info(">>> UscisPollingService.runDailyPoll()");
        List<ImmigrationCase> cases = caseRepo.findByReceiptNumberIsNotNull();
        log.info("Polling USCIS for {} cases with receipt numbers", cases.size());

        int changed = 0;
        for (ImmigrationCase c : cases) {
            try {
                if (pollAndSave(c)) changed++;
            } catch (Exception e) {
                log.warn("!!! USCIS poll failed for case {} ({}): {}", c.getId(), c.getReceiptNumber(), e.getMessage());
            }
        }
        log.info("<<< UscisPollingService.runDailyPoll() statusChanges={}", changed);
    }

    // ── Manual check-now (attorney-triggered) ────────────────────────────────

    @Transactional
    public UscisStatusDTO checkNow(Long caseId) {
        log.info(">>> uscis.checkNow() caseId={}", caseId);
        User caller = currentUser();
        permissionService.requireAccess(caller, caseId, GrantScope.READ_CASE);
        requireAttorneyInFirm(caller, caseId);

        ImmigrationCase c = caseRepo.findById(caseId)
                .orElseThrow(() -> new RuntimeException("Case not found: " + caseId));
        if (c.getReceiptNumber() == null || c.getReceiptNumber().isBlank())
            throw new RuntimeException("Case has no receipt number to poll");

        try {
            pollAndSave(c);
        } catch (IOException | InterruptedException e) {
            log.warn("!!! USCIS poll failed for caseId={}: {}", caseId, e.toString());
            throw new RuntimeException("USCIS status check failed. Please try again later.", e);
        }

        return pollRepo.findFirstByCaseIdOrderByPolledAtDesc(caseId)
                .map(this::toDTO)
                .orElseThrow(() -> new RuntimeException("No poll result saved"));
    }

    @Transactional(readOnly = true)
    public List<UscisStatusDTO> getHistory(Long caseId) {
        log.info(">>> uscis.getHistory() caseId={}", caseId);
        User caller = currentUser();
        permissionService.requireAccess(caller, caseId, GrantScope.READ_CASE);
        return pollRepo.findByCaseIdOrderByPolledAtDesc(caseId)
                .stream().map(this::toDTO).toList();
    }

    // ── Core poll logic ───────────────────────────────────────────────────────

    /** Returns true if the status changed compared to the previous poll. */
    private boolean pollAndSave(ImmigrationCase c) throws IOException, InterruptedException {
        String receiptNumber = c.getReceiptNumber();
        String html = fetchHtml(receiptNumber);
        String detectedStatus = parseStatus(html);

        Optional<UscisPollResult> previous = pollRepo.findFirstByCaseIdOrderByPolledAtDesc(c.getId());
        boolean changed = previous
                .map(p -> p.getDetectedStatus() != null
                        && !p.getDetectedStatus().equalsIgnoreCase(detectedStatus))
                .orElse(false);

        UscisPollResult result = new UscisPollResult();
        result.setCaseId(c.getId());
        result.setPolledAt(LocalDateTime.now());
        result.setRawStatusText(html.length() > 4000 ? html.substring(0, 4000) : html);
        result.setDetectedStatus(detectedStatus);
        result.setStatusChanged(changed);
        pollRepo.save(result);

        if (changed) {
            log.info("USCIS status changed for case {} ({}): '{}' → '{}'",
                    c.getId(), receiptNumber,
                    previous.map(UscisPollResult::getDetectedStatus).orElse("none"),
                    detectedStatus);
            createSystemTimelineEvent(c, detectedStatus);
            notifyAttorney(c, detectedStatus);
        }
        return changed;
    }

    private String fetchHtml(String receiptNumber) throws IOException, InterruptedException {
        String body = "appReceiptNum=" + URLEncoder.encode(receiptNumber, StandardCharsets.UTF_8)
                + "&caseStatusSearchBtn=CHECK+STATUS";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(USCIS_URL))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", "Mozilla/5.0 (compatible; ImmCaseTracker/1.0)")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    private String parseStatus(String html) {
        Matcher m = STATUS_PATTERN.matcher(html);
        return m.find() ? m.group(1).trim() : "Unknown";
    }

    private void createSystemTimelineEvent(ImmigrationCase c, String newStatus) {
        CaseEvent event = new CaseEvent();
        event.setImmigrationCase(c);
        event.setEventType(EventType.STATUS_CHANGED);
        event.setEventDate(LocalDate.now());
        event.setTitle("USCIS Status Updated");
        event.setDescription("USCIS portal now shows: " + newStatus);
        event.setSystemGenerated(true);
        eventRepo.save(event);
    }

    private void notifyAttorney(ImmigrationCase c, String newStatus) {
        String email = attorneyEmail(c.getAssignedAttorneyMemberId());
        if (email == null) return;
        String caseUrl = frontendUrl + "/immigration/cases/" + c.getId();
        try {
            emailService.sendSimpleEmail(email,
                    "USCIS Status Changed — " + c.getCaseNumber(),
                    "USCIS status for case " + c.getCaseNumber() + " (" + c.getReceiptNumber() + ") changed to:\n\n"
                    + newStatus + "\n\nView case: " + caseUrl);
        } catch (Exception e) {
            log.warn("USCIS change notification email failed for case {}: {}", c.getId(), e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        OAuth2User principal = (OAuth2User) auth.getPrincipal();
        String googleId = principal.getAttribute("sub");
        return userRepo.findByGoogleId(googleId)
                .orElseThrow(() -> new RuntimeException("Authenticated user not found"));
    }

    private void requireAttorneyInFirm(User caller, Long caseId) {
        ImmigrationCase c = caseRepo.findById(caseId)
                .orElseThrow(() -> new RuntimeException("Case not found: " + caseId));
        if (c.getLawFirmImmOrgId() == null) return;
        boolean ok = memberRepo.findByUserIdAndStatus(caller.getId(), ImmOrgMemberStatus.ACTIVE)
                .stream()
                .anyMatch(m -> m.getImmOrgId().equals(c.getLawFirmImmOrgId())
                        && (m.getRole() == ImmOrgMemberRole.ATTORNEY || m.getRole() == ImmOrgMemberRole.OWNER));
        if (!ok) throw new RuntimeException("Access denied: ATTORNEY role required to trigger USCIS poll");
    }

    private String attorneyEmail(Long memberId) {
        if (memberId == null) return null;
        return memberRepo.findById(memberId).map(ImmOrgMember::getEmail).orElse(null);
    }

    UscisStatusDTO toDTO(UscisPollResult r) {
        return new UscisStatusDTO(r.getId(), r.getCaseId(), r.getPolledAt(),
                r.getRawStatusText(), r.getDetectedStatus(), r.isStatusChanged());
    }
}
