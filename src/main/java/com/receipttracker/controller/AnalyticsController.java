package com.receipttracker.controller;

import com.receipttracker.dto.AnalyticsDTO;
import com.receipttracker.service.AnalyticsService;
import com.receipttracker.service.CashbackService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsController.class);

    @Autowired private AnalyticsService analyticsService;
    @Autowired private CashbackService cashbackService;

    @GetMapping
    public ResponseEntity<AnalyticsDTO> getAnalytics(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {

        if (from == null) from = LocalDateTime.now().minusMonths(12);
        if (to   == null) to   = LocalDateTime.now();
        
        log.info(">>> GET /api/analytics - dateRange=[{} to {}]", from, to);
        long startTime = System.currentTimeMillis();
        try {
            AnalyticsDTO result = analyticsService.getAnalytics(from, to);
            long duration = System.currentTimeMillis() - startTime;
            log.info("<<< GET /api/analytics - SUCCESS: totalSpending={}, cashbackEarned={}, receipts={}, duration={}ms", 
                    result.getTotalSpending(), result.getTotalCashbackEarned(), result.getTotalReceipts(), duration);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("!!! GET /api/analytics FAILED - duration={}ms, error={}", duration, e.getMessage(), e);
            throw e;
        }
    }

    @GetMapping("/cards")
    public ResponseEntity<Map<String, String>> getCardList() {
        log.trace(">>> GET /api/analytics/cards");
        try {
            Map<String, String> cards = cashbackService.getAllCardDisplayNames();
            log.debug("<<< GET /api/analytics/cards - SUCCESS: count={}", cards.size());
            return ResponseEntity.ok(cards);
        } catch (Exception e) {
            log.error("!!! GET /api/analytics/cards FAILED: {}", e.getMessage(), e);
            throw e;
        }
    }
}
