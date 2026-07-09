package com.receipttracker.service;

import com.receipttracker.model.JobApplication;
import com.receipttracker.model.JobReminderLog;
import com.receipttracker.repository.JobApplicationRepository;
import com.receipttracker.repository.JobReminderLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Daily job: sends a push reminder for each JobApplication whose follow-up date is due,
 * same cron slot as immigration's KeyDateReminderService. JobReminderLog guards against
 * duplicate sends if the scheduler runs more than once for the same day.
 */
@Service
public class JobFollowUpReminderService {

    private static final Logger log = LoggerFactory.getLogger(JobFollowUpReminderService.class);

    @Autowired private JobApplicationRepository jobAppRepo;
    @Autowired private JobReminderLogRepository reminderLogRepo;
    @Autowired private PushNotificationService pushService;

    @Scheduled(cron = "0 0 8 * * *")
    @Transactional
    public void runDailyReminders() {
        log.info(">>> JobFollowUpReminderService.runDailyReminders()");
        LocalDate today = LocalDate.now();
        List<JobApplication> due = jobAppRepo.findAllFollowUpsDue(today);
        log.info("Found {} job applications with a follow-up due", due.size());

        int sent = 0;
        for (JobApplication app : due) {
            try {
                if (reminderLogRepo.existsByJobApplicationIdAndRemindedDate(app.getId(), today)) continue;
                pushService.sendToUser(app.getUser(),
                        "Follow-up due: " + app.getCompanyName(),
                        "Your follow-up for " + app.getJobTitle() + " at " + app.getCompanyName() + " is due.");
                saveReminderLog(app.getId(), today);
                sent++;
            } catch (Exception e) {
                log.warn("!!! reminder failed for jobApplicationId={}: {}", app.getId(), e.getMessage());
            }
        }
        log.info("<<< JobFollowUpReminderService.runDailyReminders() sent={}", sent);
    }

    private void saveReminderLog(Long jobApplicationId, LocalDate today) {
        JobReminderLog entry = new JobReminderLog();
        entry.setJobApplicationId(jobApplicationId);
        entry.setRemindedDate(today);
        reminderLogRepo.save(entry);
    }
}
