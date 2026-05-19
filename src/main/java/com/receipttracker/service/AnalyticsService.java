package com.receipttracker.service;

import com.receipttracker.dto.AnalyticsDTO;
import com.receipttracker.dto.CategoryBreakdownDTO;
import com.receipttracker.model.Receipt;
import com.receipttracker.model.StoreType;
import com.receipttracker.model.User;
import com.receipttracker.repository.ReceiptRepository;
import com.receipttracker.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);

    @Autowired private ReceiptRepository receiptRepo;
    @Autowired private UserRepository userRepository;
    @Autowired private CashbackService cashbackService;

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        OAuth2User principal = (OAuth2User) auth.getPrincipal();
        String googleId = principal.getAttribute("sub");
        log.trace("Resolving user for analytics - googleId={}", googleId);
        return userRepository.findByGoogleId(googleId)
                .orElseThrow(() -> {
                    log.error("User not found for analytics - googleId={}", googleId);
                    return new RuntimeException("Authenticated user not found");
                });
    }

    @Transactional(readOnly = true)
    public AnalyticsDTO getAnalytics(LocalDateTime from, LocalDateTime to) {
        long startTime = System.currentTimeMillis();
        log.info("ANALYTICS: Computing analytics - dateRange=[{} to {}]", from, to);
        
        try {
            User user = currentUser();
            log.debug("  [1/5] User resolved - userId={}", user.getId());
            
            long fetchStart = System.currentTimeMillis();
            List<Receipt> receipts = receiptRepo.findByUserOrDemoAndDateBetween(user, from, to);
            log.debug("  [2/5] Receipts fetched in {}ms - count={}", System.currentTimeMillis() - fetchStart, receipts.size());

            BigDecimal totalSpending  = BigDecimal.ZERO;
            BigDecimal totalEarned    = BigDecimal.ZERO;
            BigDecimal totalPotential = BigDecimal.ZERO;

            Map<String, BigDecimal> byCategory      = new LinkedHashMap<>();
            Map<String, BigDecimal> byCard          = new LinkedHashMap<>();
            Map<String, BigDecimal> cashByCard      = new LinkedHashMap<>();
            Map<String, BigDecimal> byMonth         = new TreeMap<>();
            Map<String, String>     currentCards    = new LinkedHashMap<>();
            Map<String, Integer>    countByCategory = new LinkedHashMap<>();

            int processedCount = 0;
            for (Receipt r : receipts) {
                BigDecimal amt = r.getTotal() != null ? r.getTotal() : BigDecimal.ZERO;
                totalSpending = totalSpending.add(amt);

                String cat  = r.getStoreType() != null ? r.getStoreType().name() : "OTHER";
                String bank = r.getCardBank()  != null ? r.getCardBank()          : "UNKNOWN";

                byCategory.merge(cat, amt, BigDecimal::add);
                byCard.merge(bank, amt, BigDecimal::add);
                countByCategory.merge(cat, 1, Integer::sum);
                currentCards.putIfAbsent(cat, inferCardKey(bank));

                BigDecimal earned = cashbackService.calculateCashbackForReceipt(r);
                totalEarned = totalEarned.add(earned);
                cashByCard.merge(bank, earned, BigDecimal::add);
                totalPotential = totalPotential.add(cashbackService.calculateBestPossibleCashback(r));

                if (r.getPurchaseDateTime() != null) {
                    String month = r.getPurchaseDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM"));
                    byMonth.merge(month, amt, BigDecimal::add);
                }
                processedCount++;
            }
            
            log.debug("  [3/5] Analytics data aggregated - totalSpending={}, totalEarned={}, totalPotential={}", 
                    totalSpending, totalEarned, totalPotential);

            AnalyticsDTO dto = new AnalyticsDTO();
            dto.setTotalSpending(totalSpending);
            dto.setTotalCashbackEarned(totalEarned);
            dto.setTotalPotentialCashback(totalPotential);
            dto.setCashbackLeftOnTable(totalPotential.subtract(totalEarned).max(BigDecimal.ZERO));
            dto.setSpendingByCategory(byCategory);
            dto.setSpendingByCard(byCard);
            dto.setCashbackByCard(cashByCard);
            dto.setSpendingByMonth(byMonth);
            dto.setTotalReceipts(receipts.size());
            dto.setAvgReceiptValue(receipts.isEmpty() ? BigDecimal.ZERO
                    : totalSpending.divide(BigDecimal.valueOf(receipts.size()), 2, RoundingMode.HALF_UP));
            
            log.debug("  [4/5] DTO constructed - categories={}, cards={}, months={}", 
                    byCategory.size(), byCard.size(), byMonth.size());
            
            dto.setSuggestions(cashbackService.generateSuggestions(byCategory, currentCards));
            dto.setCategoryBreakdown(buildBreakdown(byCategory, countByCategory, totalSpending));
            
            long totalDuration = System.currentTimeMillis() - startTime;
            log.info("  [5/5] Analytics COMPLETE in {}ms - suggestions={}, breakdown.size={}", 
                    totalDuration, dto.getSuggestions().size(), dto.getCategoryBreakdown().size());
            
            return dto;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("!!! ANALYTICS failed after {}ms: {}", duration, e.getMessage(), e);
            throw e;
        }
    }

    private List<CategoryBreakdownDTO> buildBreakdown(Map<String, BigDecimal> byCategory,
                                                       Map<String, Integer> counts,
                                                       BigDecimal total) {
        return byCategory.entrySet().stream().map(e -> {
            CategoryBreakdownDTO d = new CategoryBreakdownDTO();
            d.setCategory(e.getKey());
            d.setDisplayName(friendly(e.getKey()));
            d.setAmount(e.getValue());
            d.setTransactionCount(counts.getOrDefault(e.getKey(), 0));
            d.setPercentage(total.compareTo(BigDecimal.ZERO) > 0
                    ? e.getValue().divide(total, 4, RoundingMode.HALF_UP)
                           .multiply(BigDecimal.valueOf(100)).doubleValue() : 0);
            StoreType st = safeType(e.getKey());
            d.setBestCard(cashbackService.bestCardDisplay(st));
            d.setBestCashbackRate(cashbackService.bestCardRate(st));
            return d;
        }).sorted((a, b) -> b.getAmount().compareTo(a.getAmount())).collect(Collectors.toList());
    }

    private String inferCardKey(String bank) {
        if (bank == null) return "UNKNOWN";
        return switch (bank.toUpperCase()) {
            case "CHASE"       -> "CHASE_FREEDOM_UNLIMITED";
            case "DISCOVER"    -> "DISCOVER_IT";
            case "AMEX"        -> "AMEX_BLUE_CASH_PREFERRED";
            case "CAPITAL_ONE" -> "CAPITAL_ONE_SAVOR";
            case "CITI"        -> "CITI_DOUBLE_CASH";
            default            -> "UNKNOWN";
        };
    }

    private StoreType safeType(String name) {
        try { return StoreType.valueOf(name); } catch (Exception e) { return StoreType.OTHER; }
    }

    private String friendly(String cat) {
        return switch (cat) {
            case "GAS_STATION" -> "Gas Stations";
            case "RESTAURANT"  -> "Dining";
            case "GROCERY"     -> "Groceries";
            case "COSTCO"      -> "Costco";
            case "PHARMACY"    -> "Pharmacy";
            case "ONLINE"      -> "Online";
            default            -> "Other";
        };
    }
}
