package com.receipttracker.immigration.service;

import com.receipttracker.immigration.dto.PriorityDateStatusDTO;
import com.receipttracker.immigration.dto.VisaBulletinEntryDTO;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scrapes the DOS Visa Bulletin Final Action Dates table once per month and
 * compares active EB cases' priority dates to notify attorneys when they become current.
 *
 * HTML parsing is heuristic — treats a parse failure as non-fatal (logs WARN, skips).
 */
@Service
public class VisaBulletinService {

    private static final Logger log = LoggerFactory.getLogger(VisaBulletinService.class);

    // Column order in the "Employment-Based" Final Action Dates table
    private static final String[] COUNTRIES = {"ALL_OTHER", "CHINA", "INDIA", "MEXICO", "PHILIPPINES"};
    // Row labels in the table → preference category
    private static final String[] CATEGORIES = {"EB1", "EB2", "EB3", "EB4", "EB5"};
    // date cell pattern like "01JAN18" or "22MAR20"
    private static final Pattern DATE_CELL_PATTERN =
            Pattern.compile("(\\d{2})(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)(\\d{2})");
    // <td> content extractor (strips tags)
    private static final Pattern TD_CONTENT = Pattern.compile("<td[^>]*>\\s*([^<]*?)\\s*</td>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Value("${app.frontend.url}")
    private String frontendUrl;

    @Autowired private VisaBulletinRepository bulletinRepo;
    @Autowired private ImmigrationCaseRepository caseRepo;
    @Autowired private BeneficiaryRepository beneficiaryRepo;
    @Autowired private CanonicalProfileRepository profileRepo;
    @Autowired private ImmOrgMemberRepository memberRepo;
    @Autowired private PermissionService permissionService;
    @Autowired private UserRepository userRepo;
    @Autowired private EmailService emailService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    // ── Scheduled job — 8am on the 1st of every month ─────────────────────────

    @Scheduled(cron = "0 0 8 1 * *")
    @Transactional
    public void runMonthlyBulletinScrape() {
        LocalDate today = LocalDate.now();
        log.info(">>> VisaBulletinService.runMonthlyBulletinScrape() month={}/{}", today.getMonthValue(), today.getYear());
        try {
            scrapeAndSave(today.getYear(), today.getMonthValue());
            notifyAffectedCases(today.getYear(), today.getMonthValue());
        } catch (Exception e) {
            log.warn("!!! Visa bulletin scrape failed: {}", e.getMessage());
        }
        log.info("<<< VisaBulletinService.runMonthlyBulletinScrape()");
    }

    // ── Public API ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<VisaBulletinEntryDTO> getLatest() {
        return bulletinRepo.findLatestBulletin().stream().map(this::toDTO).toList();
    }

    @Transactional(readOnly = true)
    public PriorityDateStatusDTO getPriorityDateStatus(Long caseId) {
        log.info(">>> getPriorityDateStatus() caseId={}", caseId);
        User caller = currentUser();
        permissionService.requireAccess(caller, caseId, GrantScope.READ_CASE);

        ImmigrationCase c = caseRepo.findById(caseId)
                .orElseThrow(() -> new RuntimeException("Case not found: " + caseId));
        if (c.getPriorityDate() == null)
            throw new RuntimeException("Case has no priority date set");

        String preferenceCategory = categoryForCaseType(c.getCaseType());
        if (preferenceCategory == null)
            throw new RuntimeException("Priority date tracking not supported for case type: " + c.getCaseType());

        // Get beneficiary's country of birth from canonical profile
        String countryOfBirth = beneficiaryCountry(c.getBeneficiary());
        String chargeability = toChargeability(countryOfBirth);

        List<VisaBulletinEntry> latest = bulletinRepo.findLatestBulletin();
        VisaBulletinEntry match = latest.stream()
                .filter(e -> e.getPreferenceCategory().equals(preferenceCategory)
                        && e.getCountryOfChargeability().equals(chargeability))
                .findFirst().orElse(null);

        // Also try ALL_OTHER if no specific country entry
        if (match == null) {
            match = latest.stream()
                    .filter(e -> e.getPreferenceCategory().equals(preferenceCategory)
                            && e.getCountryOfChargeability().equals("ALL_OTHER"))
                    .findFirst().orElse(null);
        }

        LocalDate cutoff = match != null ? match.getFinalActionDate() : null;
        boolean isCurrent = cutoff == null || !c.getPriorityDate().isAfter(cutoff);
        Long monthsBehind = null;
        if (!isCurrent && cutoff != null) {
            monthsBehind = ChronoUnit.MONTHS.between(c.getPriorityDate(), cutoff);
            if (monthsBehind < 0) monthsBehind = 0L;
        }

        return new PriorityDateStatusDTO(
                c.getPriorityDate(), countryOfBirth, preferenceCategory,
                cutoff, isCurrent, monthsBehind);
    }

    // ── Scraping ──────────────────────────────────────────────────────────────

    void scrapeAndSave(int year, int month) throws Exception {
        String monthName = Month.of(month).name().toLowerCase(Locale.ROOT);
        String url = "https://travel.state.gov/content/travel/en/legal/visa-law0/visa-bulletin/"
                + year + "/visa-bulletin-for-" + monthName + "-" + year + ".html";
        log.info("Fetching visa bulletin: {}", url);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "Mozilla/5.0 (compatible; ImmCaseTracker/1.0)")
                .GET().build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() != 200) {
            log.warn("Visa bulletin page returned HTTP {}", resp.statusCode());
            return;
        }

        List<VisaBulletinEntry> entries = parseEmploymentTable(resp.body(), year, month);
        if (entries.isEmpty()) {
            log.warn("No entries parsed from visa bulletin — HTML layout may have changed");
            return;
        }

        for (VisaBulletinEntry entry : entries) {
            bulletinRepo.findByBulletinYearAndBulletinMonthAndPreferenceCategoryAndCountryOfChargeability(
                            year, month, entry.getPreferenceCategory(), entry.getCountryOfChargeability())
                    .ifPresentOrElse(
                            existing -> {
                                existing.setFinalActionDate(entry.getFinalActionDate());
                                existing.setDatesForFiling(entry.getDatesForFiling());
                                existing.setScrapedAt(LocalDateTime.now());
                                bulletinRepo.save(existing);
                            },
                            () -> bulletinRepo.save(entry)
                    );
        }
        log.info("Saved {} visa bulletin entries for {}/{}", entries.size(), month, year);
    }

    private List<VisaBulletinEntry> parseEmploymentTable(String html, int year, int month) {
        List<VisaBulletinEntry> result = new ArrayList<>();
        // Find the Employment-Based Final Action Dates table
        // Heuristic: look for a table containing "Employment-Based" and "INDIA"
        int ebStart = html.indexOf("Employment-Based");
        if (ebStart < 0) return result;

        int tableStart = html.lastIndexOf("<table", ebStart);
        if (tableStart < 0) return result;

        // Find the end of this table
        int tableEnd = html.indexOf("</table>", tableStart);
        if (tableEnd < 0) return result;

        String tableHtml = html.substring(tableStart, tableEnd + 8);
        List<String[]> rows = extractTableRows(tableHtml);

        // rows[0] = header: ["Employment-Based", "All Chargeability...", "CHINA...", "INDIA", "MEXICO", "PHILIPPINES"]
        // rows[1..] = data rows: ["1st", "C", "01JAN18", "01MAR12", "C", "22AUG19"]
        if (rows.size() < 2) return result;

        // Map category ordinals (1st=EB1, 2nd=EB2, 3rd=EB3, 4th=EB4, 5th=EB5)
        int catIndex = 0;
        for (int ri = 1; ri < rows.size(); ri++) {
            String[] cells = rows.get(ri);
            if (cells.length < 2) continue;

            String rowLabel = cells[0].trim();
            if (rowLabel.matches(".*\\d+(st|nd|rd|th).*") || rowLabel.matches("^[1-5](st|nd|rd|th)$")) {
                // Advance category index
                if (rowLabel.startsWith("1") || rowLabel.contains("1st")) catIndex = 0;
                else if (rowLabel.startsWith("2") || rowLabel.contains("2nd")) catIndex = 1;
                else if (rowLabel.startsWith("3") || rowLabel.contains("3rd")) catIndex = 2;
                else if (rowLabel.startsWith("4") || rowLabel.contains("4th")) catIndex = 3;
                else if (rowLabel.startsWith("5") || rowLabel.contains("5th")) catIndex = 4;
                else continue;
            } else if (rowLabel.matches("^[0-9].*")) {
                // numeric row label — skip
                continue;
            } else {
                // Non-ordinal row: subcategory within current category (e.g. "Other Workers") — skip
                continue;
            }

            String category = catIndex < CATEGORIES.length ? CATEGORIES[catIndex] : null;
            if (category == null) continue;

            for (int ci = 1; ci < cells.length && (ci - 1) < COUNTRIES.length; ci++) {
                String rawDate = cells[ci].trim();
                LocalDate finalActionDate = parseBulletinDate(rawDate);
                VisaBulletinEntry entry = new VisaBulletinEntry();
                entry.setBulletinYear(year);
                entry.setBulletinMonth(month);
                entry.setPreferenceCategory(category);
                entry.setCountryOfChargeability(COUNTRIES[ci - 1]);
                entry.setFinalActionDate(finalActionDate); // null = C (current)
                entry.setScrapedAt(LocalDateTime.now());
                result.add(entry);
            }
        }
        return result;
    }

    private List<String[]> extractTableRows(String tableHtml) {
        List<String[]> rows = new ArrayList<>();
        // Split by <tr> tags
        String[] trParts = tableHtml.split("(?i)<tr[^>]*>");
        for (String part : trParts) {
            List<String> cells = new ArrayList<>();
            Matcher m = TD_CONTENT.matcher(part);
            while (m.find()) {
                // strip any remaining HTML tags from cell content
                cells.add(m.group(1).replaceAll("<[^>]+>", "").trim());
            }
            if (!cells.isEmpty()) {
                rows.add(cells.toArray(new String[0]));
            }
        }
        return rows;
    }

    // null = "C" (current); parses "01JAN18" format
    private LocalDate parseBulletinDate(String raw) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("C")) return null;
        Matcher m = DATE_CELL_PATTERN.matcher(raw.toUpperCase(Locale.ROOT));
        if (!m.find()) return null;
        try {
            int day  = Integer.parseInt(m.group(1));
            String monStr = m.group(2);
            int yr   = 2000 + Integer.parseInt(m.group(3));
            Month mon = Month.valueOf(monStr);
            return LocalDate.of(yr, mon, day);
        } catch (Exception e) {
            return null;
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private void notifyAffectedCases(int year, int month) {
        List<ImmigrationCase> ebCases = caseRepo.findAll().stream()
                .filter(c -> c.getPriorityDate() != null && isEbCase(c.getCaseType()))
                .toList();

        for (ImmigrationCase c : ebCases) {
            try {
                checkAndNotify(c, year, month);
            } catch (Exception e) {
                log.warn("Priority date notification failed for case {}: {}", c.getId(), e.getMessage());
            }
        }
    }

    private void checkAndNotify(ImmigrationCase c, int year, int month) {
        String category = categoryForCaseType(c.getCaseType());
        if (category == null) return;
        String country = toChargeability(beneficiaryCountry(c.getBeneficiary()));

        VisaBulletinEntry current = bulletinRepo
                .findByBulletinYearAndBulletinMonthAndPreferenceCategoryAndCountryOfChargeability(
                        year, month, category, country)
                .orElse(null);
        if (current == null) return;

        // Cutoff null = C (current) or priorityDate is on/before cutoff
        boolean nowCurrent = current.getFinalActionDate() == null
                || !c.getPriorityDate().isAfter(current.getFinalActionDate());
        if (!nowCurrent) return;

        // Check if it became current this month (previous month was not current)
        LocalDate prevCutoff = previousCutoff(year, month, category, country);
        boolean wasCurrent = prevCutoff == null || !c.getPriorityDate().isAfter(prevCutoff);
        if (wasCurrent) return; // already was current — no new notification

        // Became current this month — notify attorney
        String attorneyEmail = memberRepo.findById(c.getAssignedAttorneyMemberId() != null
                ? c.getAssignedAttorneyMemberId() : -1L)
                .map(m -> m.getEmail()).orElse(null);
        if (attorneyEmail == null) return;

        String caseUrl = frontendUrl + "/immigration/cases/" + c.getId();
        emailService.sendSimpleEmail(
                attorneyEmail,
                "Priority Date Current — " + c.getCaseNumber(),
                "Priority date " + c.getPriorityDate() + " for case " + c.getCaseNumber()
                + " (" + category + " / " + country + ") is now CURRENT in the "
                + Month.of(month).name() + " " + year + " visa bulletin.\n\nView case: " + caseUrl);
        log.info("Priority date current notification sent for case {} to {}", c.getId(), attorneyEmail);
    }

    private LocalDate previousCutoff(int year, int month, String category, String country) {
        // Go back one month
        LocalDate prev = LocalDate.of(year, month, 1).minusMonths(1);
        return bulletinRepo.findByBulletinYearAndBulletinMonthAndPreferenceCategoryAndCountryOfChargeability(
                        prev.getYear(), prev.getMonthValue(), category, country)
                .map(VisaBulletinEntry::getFinalActionDate)
                .orElse(LocalDate.of(1900, 1, 1)); // far past = "was not current"
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String categoryForCaseType(CaseType type) {
        return switch (type) {
            case I140_EB2 -> "EB2";
            case I140_EB3 -> "EB3";
            case I485     -> "EB2";  // default; refine via parentCase if needed
            case PERM     -> "EB2";
            case GC_EAD   -> "EB2";
            default       -> null;
        };
    }

    private boolean isEbCase(CaseType type) {
        return categoryForCaseType(type) != null;
    }

    private String beneficiaryCountry(Beneficiary beneficiary) {
        if (beneficiary == null) return null;
        return profileRepo.findByBeneficiary(beneficiary)
                .map(p -> p.getCountryOfBirth())
                .orElse(null);
    }

    private String toChargeability(String countryOfBirth) {
        if (countryOfBirth == null) return "ALL_OTHER";
        String upper = countryOfBirth.toUpperCase(Locale.ROOT);
        if (upper.contains("INDIA"))       return "INDIA";
        if (upper.contains("CHINA"))       return "CHINA";
        if (upper.contains("PHILIPPINES")) return "PHILIPPINES";
        if (upper.contains("MEXICO"))      return "MEXICO";
        return "ALL_OTHER";
    }

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        OAuth2User principal = (OAuth2User) auth.getPrincipal();
        String googleId = principal.getAttribute("sub");
        return userRepo.findByGoogleId(googleId)
                .orElseThrow(() -> new RuntimeException("Authenticated user not found"));
    }

    VisaBulletinEntryDTO toDTO(VisaBulletinEntry e) {
        return new VisaBulletinEntryDTO(
                e.getId(), e.getBulletinYear(), e.getBulletinMonth(),
                e.getPreferenceCategory(), e.getCountryOfChargeability(),
                e.getFinalActionDate(), e.getDatesForFiling(), e.getScrapedAt());
    }
}
