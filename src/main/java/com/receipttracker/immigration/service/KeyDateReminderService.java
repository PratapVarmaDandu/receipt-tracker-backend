package com.receipttracker.immigration.service;

import com.receipttracker.immigration.model.*;
import com.receipttracker.immigration.repository.*;
import com.receipttracker.service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Daily job: sends key-date reminder emails to assigned attorneys (and cc beneficiary
 * when notification_email_enabled) at configured day-buckets before each deadline.
 */
@Service
public class KeyDateReminderService {

    private static final Logger log = LoggerFactory.getLogger(KeyDateReminderService.class);

    private static final int[] BUCKETS = {90, 60, 30, 14, 7, 1};

    @Value("${app.frontend.url}")
    private String frontendUrl;

    @Autowired private KeyDateRepository keyDateRepo;
    @Autowired private KeyDateReminderRepository reminderRepo;
    @Autowired private ImmigrationCaseRepository caseRepo;
    @Autowired private ImmOrgMemberRepository memberRepo;
    @Autowired private CanonicalProfileRepository profileRepo;
    @Autowired private EmailService emailService;

    @Scheduled(cron = "0 0 8 * * *")
    @Transactional
    public void runDailyReminders() {
        log.info(">>> KeyDateReminderService.runDailyReminders()");
        LocalDate today = LocalDate.now();
        LocalDate windowEnd = today.plusDays(90);

        List<KeyDate> upcoming = keyDateRepo.findInWindow(today.plusDays(1), windowEnd);
        log.info("Found {} key dates in 90-day window", upcoming.size());

        int sent = 0;
        for (KeyDate kd : upcoming) {
            long daysUntil = ChronoUnit.DAYS.between(today, kd.getDate());
            try {
                sent += processKeyDate(kd, daysUntil);
            } catch (Exception e) {
                log.warn("!!! reminder failed for keyDateId={}: {}", kd.getId(), e.getMessage());
            }
        }
        log.info("<<< KeyDateReminderService.runDailyReminders() sent={}", sent);
    }

    private int processKeyDate(KeyDate kd, long daysUntil) {
        ImmigrationCase c = kd.getImmigrationCase();
        String caseUrl = frontendUrl + "/immigration/cases/" + c.getId();
        String label = kd.getLabel() != null ? kd.getLabel() : humanLabel(kd.getDateType().name());
        String caseName = c.getCaseNumber();

        int sent = 0;
        for (int bucket : BUCKETS) {
            if (daysUntil > bucket) continue; // not yet at this bucket threshold

            String attorneyEmail = attorneyEmail(c.getAssignedAttorneyMemberId());
            if (attorneyEmail != null) {
                if (!reminderRepo.existsByKeyDateAndDaysBeforeDateAndRecipientEmail(kd, bucket, attorneyEmail)) {
                    emailService.sendKeyDateReminderEmail(attorneyEmail, caseName, label, daysUntil, caseUrl);
                    saveReminder(kd, bucket, attorneyEmail);
                    sent++;
                }
            }

            // CC beneficiary if notification_email_enabled
            String beneficiaryEmail = beneficiaryEmail(c);
            if (beneficiaryEmail != null && notificationsEnabled(c) &&
                    !beneficiaryEmail.equals(attorneyEmail)) {
                if (!reminderRepo.existsByKeyDateAndDaysBeforeDateAndRecipientEmail(kd, bucket, beneficiaryEmail)) {
                    emailService.sendKeyDateReminderEmail(beneficiaryEmail, caseName, label, daysUntil, caseUrl);
                    saveReminder(kd, bucket, beneficiaryEmail);
                    sent++;
                }
            }
        }
        return sent;
    }

    private void saveReminder(KeyDate kd, int bucket, String email) {
        KeyDateReminder r = new KeyDateReminder();
        r.setKeyDate(kd);
        r.setSentAt(LocalDateTime.now());
        r.setDaysBeforeDate(bucket);
        r.setRecipientEmail(email);
        reminderRepo.save(r);
    }

    private String attorneyEmail(Long memberId) {
        if (memberId == null) return null;
        return memberRepo.findById(memberId).map(ImmOrgMember::getEmail).orElse(null);
    }

    private String beneficiaryEmail(ImmigrationCase c) {
        try {
            return c.getBeneficiary().getUser().getEmail();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean notificationsEnabled(ImmigrationCase c) {
        try {
            return profileRepo.findByBeneficiary(c.getBeneficiary())
                    .map(CanonicalProfile::isNotificationEmailEnabled)
                    .orElse(true);
        } catch (Exception e) {
            return false;
        }
    }

    private String humanLabel(String enumName) {
        StringBuilder sb = new StringBuilder();
        for (String w : enumName.split("_")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0)));
            sb.append(w.substring(1).toLowerCase());
        }
        return sb.toString();
    }
}
