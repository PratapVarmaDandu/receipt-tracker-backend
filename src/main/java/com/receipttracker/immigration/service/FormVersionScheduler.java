package com.receipttracker.immigration.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class FormVersionScheduler {

    private static final Logger log = LoggerFactory.getLogger(FormVersionScheduler.class);

    @Autowired private FormVersionService formVersionService;

    // First day of each month at 01:00 AM
    @Scheduled(cron = "0 0 1 1 * ?")
    public void monthlyFormCheck() {
        log.info("FormVersionScheduler: monthly form edition check triggered");
        formVersionService.checkForUpdates();
    }
}
