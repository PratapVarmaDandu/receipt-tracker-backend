package com.receipttracker.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SubscriptionReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionReconciliationScheduler.class);

    @Autowired private SubscriptionService subscriptionService;

    // Every day at 01:30 AM — before the 02:00 AM grace-period lockout sweep, so any
    // subscription cancellation missed via webhook is corrected before that sweep runs.
    @Scheduled(cron = "0 30 1 * * ?")
    public void dailyReconciliation() {
        log.info("SubscriptionReconciliationScheduler: daily reconciliation sweep triggered");
        subscriptionService.reconcileSubscriptions();
    }
}
