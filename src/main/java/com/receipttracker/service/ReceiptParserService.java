package com.receipttracker.service;

import com.receipttracker.dto.ParsedReceiptData;
import com.receipttracker.dto.ParsedReceiptItem;
import com.receipttracker.model.ReceiptType;
import com.receipttracker.model.StoreType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ReceiptParserService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptParserService.class);

    // ── Date formatters ───────────────────────────────────────────────────────
    private static final List<DateTimeFormatter> DT_FMTS = Arrays.asList(
            DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy h:mm a"),
            DateTimeFormatter.ofPattern("MM/dd/yy HH:mm"),
            DateTimeFormatter.ofPattern("MM-dd-yyyy HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("MM/dd/yy h:mm")
    );
    private static final List<DateTimeFormatter> D_FMTS = Arrays.asList(
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yy"),
            DateTimeFormatter.ofPattern("MM-dd-yyyy"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("MMM dd, yyyy")
    );

    // ── Regex patterns ────────────────────────────────────────────────────────
    private static final Pattern DATE_PATTERN = Pattern.compile(
            "(\\d{1,2}[/\\-]\\d{1,2}[/\\-]\\d{2,4}|\\d{4}-\\d{2}-\\d{2})" +
            "(?:\\s+(\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s*[AaPp][Mm])?))?");

    // Matches currency amounts: 12.99  $12.99  -12.99
    private static final Pattern PRICE_PATTERN =
            Pattern.compile("\\$?-?(\\d{1,5}\\.\\d{2})(?![\\d])");

    private static final Pattern LAST_FOUR_PATTERN = Pattern.compile(
            "(?:XXXX|X{4}|\\*{4}|x{4})(\\d{4})|" +
            "(?:ending in|ending|last 4|card)\\s+(?:digits?\\s+)?(\\d{4})|" +
            "(?:Acct #\\s*[*]+)(\\d{4})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CARD_TYPE_PATTERN = Pattern.compile(
            "\\b(VISA|MASTERCARD|MASTER CARD|AMEX|AMERICAN EXPRESS|DISCOVER|DEBIT)\\b",
            Pattern.CASE_INSENSITIVE);

    // Costco item — price on SAME line: "E 222490 KS XL PEANUT 15.38 N"
    private static final Pattern COSTCO_ITEM_PATTERN = Pattern.compile(
            "^[E*]?\\s+(\\d{5,8})\\s+(.+?)\\s+(\\d{1,4}\\.\\d{2})\\s*[NAEFJT]?$");

    // Costco item — price on NEXT line (description wraps): "E 1491866 PUMPKIN"
    private static final Pattern COSTCO_ITEM_START = Pattern.compile(
            "^[E*]\\s+(\\d{5,8})\\s+(.+)$");

    // Continuation line that carries the price: "SEED 53.95 N"  or just "53.95 N"
    private static final Pattern COSTCO_CONTINUATION = Pattern.compile(
            "^(.+?)\\s+(\\d{1,4}\\.\\d{2})\\s*[NAEFT]?$|^(\\d{1,4}\\.\\d{2})\\s*[NAEFT]?$");

    // Generic item: "DESCRIPTION   12.99"
    private static final Pattern GENERIC_ITEM_PATTERN = Pattern.compile(
            "^(.+?)\\s{2,}(\\d{1,4}\\.\\d{2})$");

    // Generic item with qty: "2   ITEM   25.98"
    private static final Pattern QTY_ITEM_PATTERN = Pattern.compile(
            "^(\\d{1,3})\\s+(.+?)\\s{2,}(\\d{1,4}\\.\\d{2})$");

    // Bank statement transaction line: "01/15 AMAZON.COM          -45.99"
    private static final Pattern BANK_TRANSACTION_PATTERN = Pattern.compile(
            "^(\\d{1,2}[/\\-]\\d{1,2}(?:[/\\-]\\d{2,4})?)\\s+(.{4,50}?)\\s{2,}([\\-]?\\d{1,6}\\.\\d{2})$");

    // Bank statement keywords for detection
    private static final Set<String> BANK_KEYWORDS = new HashSet<>(Arrays.asList(
            "STATEMENT", "ACCOUNT SUMMARY", "BEGINNING BALANCE", "ENDING BALANCE",
            "ACCOUNT NUMBER", "STATEMENT PERIOD", "DEPOSITS", "WITHDRAWALS",
            "TRANSACTION DATE", "POSTING DATE", "AVAILABLE BALANCE", "BANK OF AMERICA",
            "CHASE BANK", "WELLS FARGO", "CITIBANK", "CAPITAL ONE", "DISCOVER BANK"
    ));

    // Lines to always skip in item parsing
    private static final Set<String> SKIP_PREFIXES = new HashSet<>(Arrays.asList(
            "SUBTOTAL", "SUB-TOTAL", "SUB TOTAL", "TAX", "TOTAL", "THANK",
            "WELCOME", "RECEIPT", "CASHIER", "STORE", "PHONE", "DATE", "TIME",
            "ADDRESS", "MEMBER", "SAVINGS", "INSTANT SAVINGS", "CHANGE", "CASH",
            "CREDIT", "DEBIT", "AMOUNT", "BALANCE", "CARD", "VISA", "MASTERCARD",
            "AMEX", "DISCOVER", "TRANSACTION", "APPROVED", "AUTH", "REF#",
            "CUSTOMER", "MERCHANT", "ITEMS SOLD", "ITEM SOLD", "WHSE", "TRM",
            "PLEASE", "VISIT", "COSTCO", "KIRKLAND", "MEMBER NUMBER", "P7"
    ));

    // ─────────────────────────────────────────────────────────────────────────

    public ParsedReceiptData parse(String ocrText) {
        ParsedReceiptData data = new ParsedReceiptData();
        if (ocrText == null || ocrText.isBlank()) return data;

        log.debug("Parsing OCR text ({} chars):\n{}", ocrText.length(), ocrText);

        String[] lines = ocrText.split("\n");

        if (isBankStatement(ocrText)) {
            return parseBankStatement(lines, ocrText);
        }

        data.setStoreType(detectStoreType(ocrText));
        data.setStoreName(detectStoreName(lines, ocrText, data.getStoreType()));
        data.setPurchaseDateTime(extractDateTime(ocrText));
        extractCardInfo(ocrText, data);
        extractFinancials(lines, data);

        if (data.getStoreType() == StoreType.COSTCO) {
            data.setItems(extractCostcoItems(lines));
        } else {
            data.setItems(extractGenericItems(lines));
        }

        log.info("Parsed: store={} type={} total={} items={}",
                data.getStoreName(), data.getStoreType(), data.getTotal(), data.getItems().size());
        return data;
    }

    // ── Bank statement detection & parsing ───────────────────────────────────

    private boolean isBankStatement(String text) {
        String u = text.toUpperCase();
        int matches = 0;
        for (String kw : BANK_KEYWORDS) {
            if (u.contains(kw)) matches++;
        }
        return matches >= 2;
    }

    private ParsedReceiptData parseBankStatement(String[] lines, String fullText) {
        ParsedReceiptData data = new ParsedReceiptData();
        data.setReceiptType(ReceiptType.BANK_STATEMENT);
        data.setStoreType(StoreType.BANK);
        data.setStoreName(detectBankName(fullText));
        data.setPurchaseDateTime(extractDateTime(fullText));
        extractCardInfo(fullText, data);

        List<ParsedReceiptItem> transactions = new ArrayList<>();
        BigDecimal totalSpending = BigDecimal.ZERO;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            Matcher m = BANK_TRANSACTION_PATTERN.matcher(trimmed);
            if (m.find()) {
                String txDate = m.group(1);
                String description = m.group(2).trim();
                String amountStr = m.group(3).replace(",", "");

                BigDecimal amount;
                try { amount = new BigDecimal(amountStr); } catch (NumberFormatException e) { continue; }

                // Skip credits (negative amounts = money coming in on bank statements)
                if (amount.compareTo(BigDecimal.ZERO) <= 0) continue;

                ParsedReceiptItem item = new ParsedReceiptItem();
                item.setName(description);
                item.setDescription(txDate);
                item.setQuantity(1);
                item.setUnitPrice(amount);
                item.setTotalPrice(amount);
                transactions.add(item);
                totalSpending = totalSpending.add(amount);
            }
        }

        data.setItems(transactions);
        if (totalSpending.compareTo(BigDecimal.ZERO) > 0) {
            data.setTotal(totalSpending);
        }

        log.info("Parsed bank statement: bank={} transactions={} total={}",
                data.getStoreName(), transactions.size(), data.getTotal());
        return data;
    }

    private String detectBankName(String text) {
        String u = text.toUpperCase();
        if (u.contains("BANK OF AMERICA") || u.contains("BOFA")) return "Bank of America";
        if (u.contains("CHASE"))          return "Chase Bank";
        if (u.contains("WELLS FARGO"))    return "Wells Fargo";
        if (u.contains("CITIBANK") || u.contains("CITI BANK")) return "Citibank";
        if (u.contains("CAPITAL ONE"))    return "Capital One";
        if (u.contains("DISCOVER"))       return "Discover Bank";
        if (u.contains("AMEX") || u.contains("AMERICAN EXPRESS")) return "American Express";
        if (u.contains("US BANK"))        return "U.S. Bank";
        if (u.contains("TD BANK"))        return "TD Bank";
        return "Bank";
    }

    // ── Store type ────────────────────────────────────────────────────────────

    private StoreType detectStoreType(String text) {
        String u = text.toUpperCase();

        // Costco-specific receipt patterns (even without the word "COSTCO")
        if (containsAny(u, "COSTCO") ||
            u.contains("**** TOTAL") ||
            u.contains("** TOTAL") ||
            u.contains("INSTANT SAVINGS") ||
            (u.contains("TOTAL NUMBER OF ITEMS SOLD") || u.contains("ITEMS SOLD"))) {
            return StoreType.COSTCO;
        }

        if (containsAny(u, "GALLON", "UNLEADED", "DIESEL", "PUMP #", "TOTAL SALE",
                "SHELL", "CHEVRON", "EXXON", "MARATHON", "SUNOCO", "CITGO",
                "KIRKLAND SIGNATURE FUEL")) {
            return StoreType.GAS_STATION;
        }

        if (containsAny(u, "TIP", "GRATUITY", "SERVER", "TABLE #", "DINE",
                "GUEST CHECK", "MCDONALD", "STARBUCKS", "CHIPOTLE", "SUBWAY",
                "BURGER", "TACO", "PIZZA", "OLIVE GARDEN", "APPLEBEE", "CHILI")) {
            return StoreType.RESTAURANT;
        }

        if (containsAny(u, "WALMART", "WHOLE FOODS", "TRADER JOE", "KROGER", "SAFEWAY",
                "PUBLIX", "HEB", "ALDI", "GROCERY", "SUPERMARKET")) {
            return StoreType.GROCERY;
        }

        if (containsAny(u, "CVS", "WALGREEN", "RITE AID", "PHARMACY")) {
            return StoreType.PHARMACY;
        }

        return StoreType.OTHER;
    }

    // ── Store name ────────────────────────────────────────────────────────────

    private String detectStoreName(String[] lines, String fullText, StoreType type) {
        String u = fullText.toUpperCase();

        // Known brand names take priority
        if (u.contains("COSTCO WHOLESALE")) return "Costco Wholesale";
        if (type == StoreType.COSTCO)        return "Costco";
        if (u.contains("WALMART"))           return "Walmart";
        if (u.contains("WHOLE FOODS"))       return "Whole Foods Market";
        if (u.contains("TRADER JOE"))        return "Trader Joe's";
        if (u.contains("KROGER"))            return "Kroger";
        if (u.contains("TARGET"))            return "Target";
        if (u.contains("SHELL"))             return "Shell";
        if (u.contains("CHEVRON"))           return "Chevron";
        if (u.contains("EXXON"))             return "ExxonMobil";
        if (u.contains("MCDONALD"))          return "McDonald's";
        if (u.contains("CHIPOTLE"))          return "Chipotle";
        if (u.contains("STARBUCKS"))         return "Starbucks";
        if (u.contains("SUBWAY"))            return "Subway";

        // Fall back to first readable line
        for (String line : lines) {
            String t = line.trim();
            if (!t.isEmpty() && t.length() > 2
                    && t.matches(".*[a-zA-Z].*")
                    && !PRICE_PATTERN.matcher(t).find()) {
                return t;
            }
        }
        return "Unknown Store";
    }

    // ── Date/time ─────────────────────────────────────────────────────────────

    private LocalDateTime extractDateTime(String text) {
        Matcher m = DATE_PATTERN.matcher(text);
        while (m.find()) {
            String datePart = m.group(1);
            String timePart = m.group(2);
            if (timePart != null) {
                String combined = datePart + " " + timePart.trim();
                for (DateTimeFormatter fmt : DT_FMTS) {
                    try { return LocalDateTime.parse(combined, fmt); } catch (Exception ignored) {}
                }
            }
            for (DateTimeFormatter fmt : D_FMTS) {
                try { return LocalDate.parse(datePart, fmt).atStartOfDay(); } catch (Exception ignored) {}
            }
        }
        return LocalDateTime.now();
    }

    // ── Card info ─────────────────────────────────────────────────────────────

    private void extractCardInfo(String text, ParsedReceiptData data) {
        String u = text.toUpperCase();

        Matcher cm = CARD_TYPE_PATTERN.matcher(u);
        if (cm.find()) {
            String found = cm.group(1).toUpperCase();
            if (found.contains("MASTER"))                        data.setCardType("MASTERCARD");
            else if (found.contains("AMERICAN") || found.contains("AMEX")) data.setCardType("AMEX");
            else                                                 data.setCardType(found);
        }
        // Costco gas receipts abbreviate Visa as "VI Acct #"
        if (data.getCardType() == null
                && (u.contains("VI ACCT") || u.matches("(?s).*\\bVI\\s+ACCT\\b.*"))) {
            data.setCardType("VISA");
        }

        if (u.contains("CHASE"))                            data.setCardBank("CHASE");
        else if (u.contains("DISCOVER"))                    data.setCardBank("DISCOVER");
        else if (u.contains("AMEX") || u.contains("AMERICAN EXPRESS")) data.setCardBank("AMEX");
        else if (u.contains("CAPITAL ONE") || u.contains("CAPITAL1")) data.setCardBank("CAPITAL_ONE");
        else if (u.contains("CITI"))                        data.setCardBank("CITI");
        else if (u.contains("BANK OF AMERICA") || u.contains("BOFA")) data.setCardBank("BANK_OF_AMERICA");
        else if (u.contains("WELLS FARGO"))                 data.setCardBank("WELLS_FARGO");

        Matcher lf = LAST_FOUR_PATTERN.matcher(text);
        if (lf.find()) {
            String digits = lf.group(1) != null ? lf.group(1)
                          : lf.group(2) != null ? lf.group(2)
                          : lf.group(3);
            data.setLastFourDigits(digits);
        }

        String bank = data.getCardBank()       != null ? data.getCardBank()       : "";
        String type = data.getCardType()       != null ? data.getCardType()       : "";
        String four = data.getLastFourDigits() != null ? "_" + data.getLastFourDigits() : "";
        if (!bank.isEmpty() || !type.isEmpty()) {
            data.setPaymentCard((bank + (bank.isEmpty() ? "" : "_") + type + four)
                    .replaceAll("^_|_$", ""));
        }
    }

    // ── Financials ────────────────────────────────────────────────────────────

    private void extractFinancials(String[] lines, ParsedReceiptData data) {
        for (String line : lines) {
            String t = line.trim();
            String u = t.toUpperCase();

            // ── TOTAL ──
            // Priority 1: Costco "****  TOTAL  51.33" or "** TOTAL 49.43"
            if (u.matches(".*\\*+\\s+TOTAL\\s+.*") || u.matches(".*\\*+TOTAL\\s+.*")) {
                BigDecimal v = lastPrice(t);
                if (v != null && v.compareTo(BigDecimal.ZERO) > 0) {
                    data.setTotal(v);
                    continue;
                }
            }

            // Priority 2: "TOTAL SALE", "TOTAL: XX", bare "TOTAL XX"
            // Exclude: TOTAL TAX, TOTAL NUMBER, TOTAL SAVINGS, TOTAL ITEMS
            if (u.startsWith("TOTAL") && !isTotalExclusion(u)) {
                BigDecimal v = lastPrice(t);
                if (v != null && v.compareTo(BigDecimal.ZERO) > 0
                        && (data.getTotal() == null || data.getTotal().compareTo(BigDecimal.ZERO) == 0)) {
                    data.setTotal(v);
                    continue;
                }
            }

            // Priority 3: GRAND TOTAL, AMOUNT DUE, AMOUNT TENDERED
            if (containsAny(u, "GRAND TOTAL", "AMOUNT DUE", "AMOUNT TENDERED")) {
                BigDecimal v = lastPrice(t);
                if (v != null && v.compareTo(BigDecimal.ZERO) > 0) {
                    data.setTotal(v);
                    continue;
                }
            }

            // Priority 4: "AMOUNT: $51.33" (payment confirmation line on Costco)
            if ((u.startsWith("AMOUNT:") || u.startsWith("AMOUNT :"))
                    && (data.getTotal() == null || data.getTotal().compareTo(BigDecimal.ZERO) == 0)) {
                BigDecimal v = lastPrice(t);
                if (v != null && v.compareTo(BigDecimal.ZERO) > 0) {
                    data.setTotal(v);
                    continue;
                }
            }

            // ── SUBTOTAL ──
            if (u.contains("SUBTOTAL") || u.contains("SUB TOTAL") || u.contains("SUB-TOTAL")) {
                BigDecimal v = lastPrice(t);
                if (v != null) data.setSubtotal(v);
            }

            // ── TAX ──
            // Accept $0 tax (common at Costco). Exclude compound phrases.
            if (u.contains("TAX") && !u.contains("TOTAL TAX") && !u.contains("PRETAX")
                    && !u.contains("TAX EXEMPT") && !u.contains("TAX ID")) {
                BigDecimal v = lastPrice(t);
                if (v != null) {
                    data.setTax(data.getTax() == null ? v : data.getTax().add(v));
                }
            }

            // ── TIP ──
            if ((u.contains("TIP") || u.contains("GRATUITY"))
                    && !u.contains("RECEIPT")) {
                BigDecimal v = lastPrice(t);
                if (v != null && v.compareTo(BigDecimal.ZERO) > 0) data.setTip(v);
            }
        }

        // Fallback: if total is still zero or null, calculate from subtotal
        if ((data.getTotal() == null || data.getTotal().compareTo(BigDecimal.ZERO) == 0)
                && data.getSubtotal() != null && data.getSubtotal().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal t = data.getSubtotal();
            if (data.getTax()  != null) t = t.add(data.getTax());
            if (data.getTip()  != null) t = t.add(data.getTip());
            data.setTotal(t);
            log.info("Total derived from subtotal+tax: {}", t);
        }
    }

    /** Returns true for "TOTAL TAX", "TOTAL NUMBER", "TOTAL SAVINGS", "TOTAL ITEMS SOLD" etc. */
    private boolean isTotalExclusion(String u) {
        return u.contains("TOTAL TAX")
            || u.contains("TOTAL NUMBER")
            || u.contains("TOTAL SAVINGS")
            || u.contains("TOTAL ITEMS")
            || u.contains("TOTAL SOLD")
            || u.contains("TOTAL DISCOUNT");
    }

    // ── Costco item extraction ────────────────────────────────────────────────
    // Format: [E|*] ITEMCODE  DESCRIPTION  PRICE  [N|A|E|F]
    // Discounts: "379533 / 222490  4.00-"

    private List<ParsedReceiptItem> extractCostcoItems(String[] lines) {
        List<ParsedReceiptItem> items = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            String upper = line.toUpperCase();
            // Skip if it's a financial/footer line
            if (SKIP_PREFIXES.stream().anyMatch(upper::startsWith)) continue;
            // Skip discount lines (e.g. "379533 / 222490 4.00-")
            if (line.contains("/") && line.endsWith("-")) continue;
            if (line.endsWith("-")) continue;

            // Case A — price on same line: "E 222490 KS XL PEANUT 15.38 N"
            Matcher m = COSTCO_ITEM_PATTERN.matcher(line);
            if (m.find()) {
                String name = m.group(2).trim();
                BigDecimal price;
                try { price = new BigDecimal(m.group(3)); } catch (NumberFormatException e) { continue; }

                ParsedReceiptItem item = new ParsedReceiptItem();
                item.setName(name);
                item.setQuantity(1);
                item.setUnitPrice(price);
                item.setTotalPrice(price);
                items.add(item);
                continue;
            }

            // Case B — price on next line (description wrapped):
            // Line i  : "E 1491866 PUMPKIN"
            // Line i+1: "SEED 53.95 N"
            Matcher ms = COSTCO_ITEM_START.matcher(line);
            if (ms.find() && i + 1 < lines.length) {
                String namePart1 = ms.group(2).trim();
                String nextLine  = lines[i + 1].trim();

                Matcher mc = COSTCO_CONTINUATION.matcher(nextLine);
                if (mc.find()) {
                    // group 1 = desc continuation, group 2 = price (when desc present)
                    // group 3 = price only (no extra desc)
                    String namePart2 = mc.group(1) != null ? mc.group(1).trim() : "";
                    String priceStr  = mc.group(2) != null ? mc.group(2) : mc.group(3);
                    if (priceStr != null) {
                        BigDecimal price;
                        try { price = new BigDecimal(priceStr); } catch (NumberFormatException e) { continue; }
                        String fullName = namePart2.isEmpty() ? namePart1
                                        : (namePart1 + " " + namePart2).trim();
                        ParsedReceiptItem item = new ParsedReceiptItem();
                        item.setName(fullName);
                        item.setQuantity(1);
                        item.setUnitPrice(price);
                        item.setTotalPrice(price);
                        items.add(item);
                        i++; // consumed continuation line
                    }
                }
            }
        }
        return items;
    }

    // ── Generic item extraction ───────────────────────────────────────────────

    private List<ParsedReceiptItem> extractGenericItems(String[] lines) {
        List<ParsedReceiptItem> items = new ArrayList<>();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String upper = trimmed.toUpperCase();
            if (SKIP_PREFIXES.stream().anyMatch(upper::startsWith)) continue;

            // qty + item
            Matcher m2 = QTY_ITEM_PATTERN.matcher(trimmed);
            if (m2.find()) {
                int qty = Integer.parseInt(m2.group(1));
                String name = m2.group(2).trim();
                BigDecimal total = new BigDecimal(m2.group(3));
                if (name.length() >= 2 && name.matches(".*[a-zA-Z].*")) {
                    ParsedReceiptItem item = new ParsedReceiptItem();
                    item.setName(name);
                    item.setQuantity(qty);
                    item.setTotalPrice(total);
                    item.setUnitPrice(total.divide(BigDecimal.valueOf(qty), 2, RoundingMode.HALF_UP));
                    items.add(item);
                    continue;
                }
            }

            // item + price
            Matcher m1 = GENERIC_ITEM_PATTERN.matcher(trimmed);
            if (m1.find()) {
                String name = m1.group(1).trim();
                BigDecimal price = new BigDecimal(m1.group(2));
                if (name.length() >= 2 && name.matches(".*[a-zA-Z].*")) {
                    ParsedReceiptItem item = new ParsedReceiptItem();
                    item.setName(name);
                    item.setQuantity(1);
                    item.setTotalPrice(price);
                    item.setUnitPrice(price);
                    items.add(item);
                }
            }
        }
        return items;
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private BigDecimal lastPrice(String line) {
        Matcher m = PRICE_PATTERN.matcher(line);
        BigDecimal last = null;
        while (m.find()) {
            try { last = new BigDecimal(m.group(1)); } catch (NumberFormatException ignored) {}
        }
        return last;
    }

    private boolean containsAny(String text, String... words) {
        for (String w : words) if (text.contains(w)) return true;
        return false;
    }
}
