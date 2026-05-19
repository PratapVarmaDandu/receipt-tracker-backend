package com.receipttracker.service;

import com.receipttracker.dto.CashbackSuggestionDTO;
import com.receipttracker.model.Receipt;
import com.receipttracker.model.StoreType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
public class CashbackService {

    private static final Logger log = LoggerFactory.getLogger(CashbackService.class);

    // card name → (category → cashback%)
    private static final Map<String, Map<String, Double>> CARD_RATES = new LinkedHashMap<>();
    // card name → display label
    private static final Map<String, String> CARD_DISPLAY = new LinkedHashMap<>();

    static {
        CARD_DISPLAY.put("AMEX_BLUE_CASH_PREFERRED",   "Amex Blue Cash Preferred");
        CARD_DISPLAY.put("CAPITAL_ONE_SAVOR",           "Capital One Savor");
        CARD_DISPLAY.put("CITI_COSTCO_VISA",            "Costco Anywhere Visa (Citi)");
        CARD_DISPLAY.put("CHASE_FREEDOM_UNLIMITED",     "Chase Freedom Unlimited");
        CARD_DISPLAY.put("CHASE_SAPPHIRE_PREFERRED",    "Chase Sapphire Preferred");
        CARD_DISPLAY.put("DISCOVER_IT",                 "Discover it Cash Back");
        CARD_DISPLAY.put("CITI_DOUBLE_CASH",            "Citi Double Cash");
        CARD_DISPLAY.put("CAPITAL_ONE_QUICKSILVER",     "Capital One Quicksilver");

        // Amex Blue Cash Preferred
        Map<String, Double> amex = new LinkedHashMap<>();
        amex.put("GROCERY", 6.0);
        amex.put("GAS_STATION", 3.0);
        amex.put("OTHER", 1.0);
        CARD_RATES.put("AMEX_BLUE_CASH_PREFERRED", amex);

        // Capital One Savor
        Map<String, Double> savor = new LinkedHashMap<>();
        savor.put("RESTAURANT", 4.0);
        savor.put("GROCERY", 3.0);
        savor.put("OTHER", 1.0);
        CARD_RATES.put("CAPITAL_ONE_SAVOR", savor);

        // Citi Costco Anywhere Visa
        Map<String, Double> costco = new LinkedHashMap<>();
        costco.put("GAS_STATION", 4.0);
        costco.put("RESTAURANT", 3.0);
        costco.put("COSTCO", 2.0);
        costco.put("OTHER", 1.0);
        CARD_RATES.put("CITI_COSTCO_VISA", costco);

        // Chase Freedom Unlimited
        Map<String, Double> cfu = new LinkedHashMap<>();
        cfu.put("RESTAURANT", 3.0);
        cfu.put("PHARMACY", 3.0);
        cfu.put("OTHER", 1.5);
        CARD_RATES.put("CHASE_FREEDOM_UNLIMITED", cfu);

        // Chase Sapphire Preferred
        Map<String, Double> csp = new LinkedHashMap<>();
        csp.put("RESTAURANT", 3.0);
        csp.put("GROCERY", 3.0);
        csp.put("OTHER", 1.0);
        CARD_RATES.put("CHASE_SAPPHIRE_PREFERRED", csp);

        // Discover it (rotating, shown at average 3% for restaurant/gas/grocery)
        Map<String, Double> discover = new LinkedHashMap<>();
        discover.put("RESTAURANT", 5.0);
        discover.put("GAS_STATION", 5.0);
        discover.put("GROCERY", 5.0);
        discover.put("OTHER", 1.0);
        CARD_RATES.put("DISCOVER_IT", discover);

        // Citi Double Cash
        Map<String, Double> doubleCash = new LinkedHashMap<>();
        doubleCash.put("OTHER", 2.0);
        CARD_RATES.put("CITI_DOUBLE_CASH", doubleCash);

        // Capital One Quicksilver
        Map<String, Double> quicksilver = new LinkedHashMap<>();
        quicksilver.put("OTHER", 1.5);
        CARD_RATES.put("CAPITAL_ONE_QUICKSILVER", quicksilver);
    }

    public BigDecimal calculateCashbackForReceipt(Receipt receipt) {
        if (receipt.getTotal() == null) {
            log.trace("calculateCashbackForReceipt - Total is null, returning 0");
            return BigDecimal.ZERO;
        }
        String card = inferCardKey(receipt.getCardBank(), receipt.getCardType());
        BigDecimal cashback = applyRate(card, receipt.getStoreType(), receipt.getTotal());
        log.debug("Cashback calculated - receiptId={}, card={}, store={}, amount={}, cashback={}", 
                receipt.getId(), card, receipt.getStoreType(), receipt.getTotal(), cashback);
        return cashback;
    }

    public BigDecimal calculateBestPossibleCashback(Receipt receipt) {
        if (receipt.getTotal() == null) {
            log.trace("calculateBestPossibleCashback - Total is null, returning 0");
            return BigDecimal.ZERO;
        }
        String best = bestCardForCategory(receipt.getStoreType());
        BigDecimal cashback = applyRate(best, receipt.getStoreType(), receipt.getTotal());
        log.trace("Best possible cashback calculated - receiptId={}, bestCard={}, store={}, amount={}, potential={}", 
                receipt.getId(), best, receipt.getStoreType(), receipt.getTotal(), cashback);
        return cashback;
    }

    public String bestCardForCategory(StoreType storeType) {
        String cat = storeType != null ? storeType.name() : "OTHER";
        String best = null;
        double bestRate = 0;
        for (Map.Entry<String, Map<String, Double>> e : CARD_RATES.entrySet()) {
            double rate = e.getValue().getOrDefault(cat, e.getValue().getOrDefault("OTHER", 1.0));
            if (rate > bestRate) { bestRate = rate; best = e.getKey(); }
        }
        return best;
    }

    public String bestCardDisplay(StoreType storeType) {
        return CARD_DISPLAY.getOrDefault(bestCardForCategory(storeType), "Unknown");
    }

    public String bestCardRate(StoreType storeType) {
        String card = bestCardForCategory(storeType);
        if (card == null) return "1%";
        String cat = storeType != null ? storeType.name() : "OTHER";
        Map<String, Double> rates = CARD_RATES.get(card);
        double r = rates.getOrDefault(cat, rates.getOrDefault("OTHER", 1.0));
        return r + "%";
    }

    public List<CashbackSuggestionDTO> generateSuggestions(Map<String, BigDecimal> spendingByCategory,
                                                            Map<String, String> currentCards) {
        List<CashbackSuggestionDTO> suggestions = new ArrayList<>();

        for (Map.Entry<String, BigDecimal> entry : spendingByCategory.entrySet()) {
            String cat = entry.getKey();
            BigDecimal monthly = entry.getValue();
            if (monthly.compareTo(BigDecimal.valueOf(10)) < 0) continue;

            StoreType type = safeStoreType(cat);
            String bestCard = bestCardForCategory(type);
            if (bestCard == null) continue;

            String currentCard = currentCards.getOrDefault(cat, "UNKNOWN");
            String currentCardDisplay = CARD_DISPLAY.getOrDefault(currentCard, currentCard);
            double currentRate = currentRate(currentCard, type);
            double bestRate   = rateForCard(bestCard, type);

            if (bestRate <= currentRate) continue; // already optimal

            BigDecimal currentCashback   = monthly.multiply(BigDecimal.valueOf(currentRate / 100)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal potentialCashback = monthly.multiply(BigDecimal.valueOf(bestRate / 100)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal extra = potentialCashback.subtract(currentCashback);

            CashbackSuggestionDTO s = new CashbackSuggestionDTO();
            s.setCategory(cat);
            s.setDisplayCategory(friendlyCategory(cat));
            s.setCurrentCard(currentCardDisplay);
            s.setCurrentCashbackRate(currentRate + "%");
            s.setRecommendedCard(CARD_DISPLAY.get(bestCard));
            s.setRecommendedCashbackRate(bestRate + "%");
            s.setMonthlySpending(monthly);
            s.setAdditionalMonthlyEarning(extra);
            s.setAnnualSavings(extra.multiply(BigDecimal.valueOf(12)).setScale(2, RoundingMode.HALF_UP));
            s.setReason("Switching to " + CARD_DISPLAY.get(bestCard) + " gives " + bestRate
                    + "% back on " + friendlyCategory(cat) + " vs your current " + currentRate + "%");
            suggestions.add(s);
        }

        suggestions.sort((a, b) -> b.getAnnualSavings().compareTo(a.getAnnualSavings()));
        return suggestions;
    }

    // --- helpers ---

    private BigDecimal applyRate(String cardKey, StoreType storeType, BigDecimal amount) {
        Map<String, Double> rates = CARD_RATES.getOrDefault(cardKey, CARD_RATES.get("CITI_DOUBLE_CASH"));
        String cat = storeType != null ? storeType.name() : "OTHER";
        double rate = rates.getOrDefault(cat, rates.getOrDefault("OTHER", 1.0));
        return amount.multiply(BigDecimal.valueOf(rate / 100)).setScale(2, RoundingMode.HALF_UP);
    }

    private double rateForCard(String cardKey, StoreType type) {
        Map<String, Double> rates = CARD_RATES.get(cardKey);
        if (rates == null) return 1.0;
        String cat = type != null ? type.name() : "OTHER";
        return rates.getOrDefault(cat, rates.getOrDefault("OTHER", 1.0));
    }

    private double currentRate(String cardKey, StoreType type) {
        if (cardKey == null || cardKey.equals("UNKNOWN")) return 1.0;
        return rateForCard(cardKey, type);
    }

    private String inferCardKey(String bank, String type) {
        if (bank == null) return "CITI_DOUBLE_CASH";
        return switch (bank.toUpperCase()) {
            case "CHASE"        -> "CHASE_FREEDOM_UNLIMITED";
            case "DISCOVER"     -> "DISCOVER_IT";
            case "AMEX"         -> "AMEX_BLUE_CASH_PREFERRED";
            case "CAPITAL_ONE"  -> "CAPITAL_ONE_SAVOR";
            case "CITI"         -> "CITI_DOUBLE_CASH";
            default             -> "CITI_DOUBLE_CASH";
        };
    }

    private StoreType safeStoreType(String name) {
        try { return StoreType.valueOf(name); } catch (Exception e) { return StoreType.OTHER; }
    }

    private String friendlyCategory(String cat) {
        return switch (cat) {
            case "GAS_STATION" -> "Gas Stations";
            case "RESTAURANT"  -> "Dining / Restaurants";
            case "GROCERY"     -> "Groceries";
            case "COSTCO"      -> "Costco";
            case "PHARMACY"    -> "Pharmacy";
            case "ONLINE"      -> "Online Shopping";
            default            -> "Other";
        };
    }

    public Map<String, String> getAllCardDisplayNames() {
        return Collections.unmodifiableMap(CARD_DISPLAY);
    }
}
