package com.receipttracker.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SubscriptionLockoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionLockoutScheduler.class);

    @Autowired private SubscriptionService subscriptionService;

    // Every day at 02:00 AM
    @Scheduled(cron = "0 0 2 * * ?")
    public void dailyGracePeriodSweep() {
        log.info("SubscriptionLockoutScheduler: daily grace-period sweep triggered");
        subscriptionService.runGracePeriodSweep();
    }
}
