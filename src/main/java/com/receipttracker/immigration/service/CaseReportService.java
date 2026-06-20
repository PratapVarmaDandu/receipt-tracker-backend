package com.receipttracker.immigration.service;

import com.receipttracker.immigration.model.*;
import com.receipttracker.immigration.repository.*;
import com.receipttracker.model.User;
import com.receipttracker.repository.UserRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class CaseReportService {

    private static final Logger log = LoggerFactory.getLogger(CaseReportService.class);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy");
    private static final float ML  = 50f;   // margin left
    private static final float MR  = 545f;  // margin right
    private static final float TOP = 762f;
    private static final float BOT = 50f;
    private static final float LH  = 14f;   // line height

    @Autowired private ImmigrationCaseRepository caseRepo;
    @Autowired private ImmOrgRepository immOrgRepo;
    @Autowired private ImmOrgMemberRepository memberRepo;
    @Autowired private StatusHistoryRepository statusHistoryRepo;
    @Autowired private CaseEventRepository eventRepo;
    @Autowired private KeyDateRepository keyDateRepo;
    @Autowired private PermissionService permissionService;
    @Autowired private UserRepository userRepo;

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        OAuth2User principal = (OAuth2User) auth.getPrincipal();
        String googleId = principal.getAttribute("sub");
        return userRepo.findByGoogleId(googleId)
                .orElseThrow(() -> new RuntimeException("Authenticated user not found"));
    }

    @Transactional(readOnly = true)
    public byte[] generateCaseReport(Long caseId) throws IOException {
        log.info(">>> generateCaseReport() caseId={}", caseId);
        User caller = currentUser();
        permissionService.requireAccess(caller, caseId, GrantScope.READ_CASE);

        ImmigrationCase c = caseRepo.findById(caseId)
                .orElseThrow(() -> new RuntimeException("Case not found: " + caseId));
        List<StatusHistory> history = statusHistoryRepo.findByImmigrationCaseOrderByChangedAtDesc(c);
        List<CaseEvent> events = eventRepo.findByImmigrationCaseOrderByEventDateDesc(c);
        List<KeyDate> keyDates = keyDateRepo.findByImmigrationCaseOrderByDateAsc(c);

        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font bold   = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font reg    = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font italic = new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);

            // ── Page 1: Case summary ─────────────────────────────────────────
            float y = buildPage1(doc, bold, reg, c, keyDates);

            // ── Page 2: Status history ────────────────────────────────────────
            buildPage2(doc, bold, reg, italic, c, history);

            // ── Page 3: Timeline ──────────────────────────────────────────────
            buildPage3(doc, bold, reg, c, events);

            doc.save(out);
            log.info("<<< generateCaseReport() caseId={} bytes={}", caseId, out.size());
            return out.toByteArray();
        }
    }

    @Transactional(readOnly = true)
    public byte[] generateTimelinePdf(Long caseId) throws IOException {
        log.info(">>> generateTimelinePdf() caseId={}", caseId);
        User caller = currentUser();
        permissionService.requireAccess(caller, caseId, GrantScope.READ_CASE);

        ImmigrationCase c = caseRepo.findById(caseId)
                .orElseThrow(() -> new RuntimeException("Case not found: " + caseId));
        List<CaseEvent> events = eventRepo.findByImmigrationCaseOrderByEventDateDesc(c);

        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font reg  = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            buildPage3(doc, bold, reg, c, events);
            doc.save(out);
            return out.toByteArray();
        }
    }

    // ── Page builders ─────────────────────────────────────────────────────────

    private float buildPage1(PDDocument doc, PDType1Font bold, PDType1Font reg,
                             ImmigrationCase c, List<KeyDate> keyDates) throws IOException {
        PDPage page = new PDPage(PDRectangle.LETTER);
        doc.addPage(page);
        float y = TOP;

        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            // Header bar
            cs.setNonStrokingColor(0.20f, 0.35f, 0.70f);
            cs.addRect(ML - 10, y - 5, MR - ML + 20, 40);
            cs.fill();

            cs.setNonStrokingColor(1f, 1f, 1f);
            y = drawText(cs, bold, 16, ML, y + 12, "Immigration Case Report");
            cs.setNonStrokingColor(1f, 1f, 1f);
            drawTextRight(cs, reg, 9, MR, y + 14, "Generated " + LocalDate.now().format(DATE_FMT));
            y -= 45;

            cs.setNonStrokingColor(0f, 0f, 0f);

            // Case header
            y = section(cs, bold, reg, y, "CASE OVERVIEW", new String[][]{
                    {"Case Number",  c.getCaseNumber()},
                    {"Type",         formatEnum(c.getCaseType().name())},
                    {"Status",       formatEnum(c.getStatus().name())},
                    {"Opened",       c.getCreatedAt().toLocalDate().format(DATE_FMT)},
                    {"Priority Date", c.getPriorityDate() != null ? c.getPriorityDate().format(DATE_FMT) : "—"},
                    {"USCIS Receipt",  c.getReceiptNumber() != null ? c.getReceiptNumber() : "—"},
                    {"I-140 Approved", c.isI140Approved() ? "Yes" + (c.getI140ApprovedDate() != null ? " (" + c.getI140ApprovedDate().format(DATE_FMT) + ")" : "") : "No"},
            });

            // Parties
            y -= 10;
            String benefName  = c.getBeneficiary() != null && c.getBeneficiary().getUser() != null
                    ? c.getBeneficiary().getUser().getName() + " <" + c.getBeneficiary().getUser().getEmail() + ">" : "—";
            String empName    = c.getEmployerImmOrgId() != null
                    ? immOrgRepo.findById(c.getEmployerImmOrgId()).map(ImmOrg::getName).orElse("—") : "—";
            String firmName   = c.getLawFirmImmOrgId() != null
                    ? immOrgRepo.findById(c.getLawFirmImmOrgId()).map(ImmOrg::getName).orElse("—") : "—";
            String attName    = c.getAssignedAttorneyMemberId() != null
                    ? memberRepo.findById(c.getAssignedAttorneyMemberId()).map(m -> m.getEmail()).orElse("—") : "—";

            y = section(cs, bold, reg, y, "PARTIES", new String[][]{
                    {"Beneficiary",  benefName},
                    {"Employer",     empName},
                    {"Law Firm",     firmName},
                    {"Attorney",     attName},
            });

            // Key dates
            if (!keyDates.isEmpty()) {
                y -= 10;
                y = sectionHeader(cs, bold, y, "KEY DATES");
                for (KeyDate kd : keyDates) {
                    if (y < BOT + 20) break;
                    String label = kd.getLabel() != null ? kd.getLabel() : formatEnum(kd.getDateType().name());
                    y = row(cs, reg, y, label, kd.getDate() != null ? kd.getDate().format(DATE_FMT) : "—");
                }
            }
        }
        return y;
    }

    private void buildPage2(PDDocument doc, PDType1Font bold, PDType1Font reg, PDType1Font italic,
                            ImmigrationCase c, List<StatusHistory> history) throws IOException {
        PDPage page = new PDPage(PDRectangle.LETTER);
        doc.addPage(page);
        float y = TOP;

        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            pageHeader(cs, bold, reg, y, "Status History — " + c.getCaseNumber());
            y -= 50;

            cs.setNonStrokingColor(0f, 0f, 0f);
            // Column headers
            y = tableHeaderRow(cs, bold, y, new String[]{"Date", "From", "To"}, new float[]{ML, 200, 360});
            cs.setStrokingColor(0.8f, 0.8f, 0.8f);
            cs.moveTo(ML, y + 2); cs.lineTo(MR, y + 2); cs.stroke();
            y -= 4;

            for (StatusHistory sh : history) {
                if (y < BOT + 15) break;
                String date = sh.getChangedAt().toLocalDate().format(DATE_FMT);
                String from = sh.getFromStatus() != null ? formatEnum(sh.getFromStatus()) : "—";
                String to   = formatEnum(sh.getToStatus());
                y = tableRow(cs, reg, y, new String[]{date, from, to}, new float[]{ML, 200, 360});
            }

            if (history.isEmpty()) {
                cs.beginText();
                cs.setFont(italic, 10);
                cs.newLineAtOffset(ML, y);
                cs.showText("No status history recorded yet.");
                cs.endText();
            }
        }
    }

    private void buildPage3(PDDocument doc, PDType1Font bold, PDType1Font reg,
                            ImmigrationCase c, List<CaseEvent> events) throws IOException {
        PDPage page = new PDPage(PDRectangle.LETTER);
        doc.addPage(page);
        float y = TOP;

        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            pageHeader(cs, bold, reg, y, "Case Timeline — " + c.getCaseNumber());
            y -= 50;

            cs.setNonStrokingColor(0f, 0f, 0f);
            y = tableHeaderRow(cs, bold, y, new String[]{"Date", "Event", "Notes"}, new float[]{ML, 180, 330});
            cs.setStrokingColor(0.8f, 0.8f, 0.8f);
            cs.moveTo(ML, y + 2); cs.lineTo(MR, y + 2); cs.stroke();
            y -= 4;

            for (CaseEvent ev : events) {
                if (y < BOT + 15) break;
                String date  = ev.getEventDate() != null ? ev.getEventDate().format(DATE_FMT) : "—";
                String title = ev.getTitle() != null ? truncate(ev.getTitle(), 35) : formatEnum(ev.getEventType().name());
                String desc  = ev.getDescription() != null ? truncate(ev.getDescription(), 45) : "";
                y = tableRow(cs, reg, y, new String[]{date, title, desc}, new float[]{ML, 180, 330});
            }

            if (events.isEmpty()) {
                cs.beginText();
                cs.setFont(reg, 10);
                cs.newLineAtOffset(ML, y);
                cs.showText("No timeline events recorded yet.");
                cs.endText();
            }
        }
    }

    // ── Drawing helpers ────────────────────────────────────────────────────────

    private void pageHeader(PDPageContentStream cs, PDType1Font bold, PDType1Font reg,
                            float y, String title) throws IOException {
        cs.setNonStrokingColor(0.20f, 0.35f, 0.70f);
        cs.addRect(ML - 10, y - 5, MR - ML + 20, 40);
        cs.fill();
        cs.setNonStrokingColor(1f, 1f, 1f);
        drawText(cs, bold, 14, ML, y + 12, title);
        drawTextRight(cs, reg, 9, MR, y + 12, LocalDate.now().format(DATE_FMT));
    }

    private float section(PDPageContentStream cs, PDType1Font bold, PDType1Font reg,
                          float y, String title, String[][] rows) throws IOException {
        y = sectionHeader(cs, bold, y, title);
        for (String[] r : rows) {
            if (y < BOT + 15) break;
            y = row(cs, reg, y, r[0], r[1]);
        }
        return y;
    }

    private float sectionHeader(PDPageContentStream cs, PDType1Font bold, float y, String title) throws IOException {
        y -= 8;
        cs.setNonStrokingColor(0.20f, 0.35f, 0.70f);
        cs.addRect(ML - 5, y - 3, MR - ML + 10, 16);
        cs.fill();
        cs.setNonStrokingColor(1f, 1f, 1f);
        drawText(cs, bold, 10, ML, y + 2, title);
        cs.setNonStrokingColor(0f, 0f, 0f);
        return y - LH - 4;
    }

    private float row(PDPageContentStream cs, PDType1Font font, float y, String label, String value) throws IOException {
        cs.beginText();
        cs.setFont(font, 10);
        cs.newLineAtOffset(ML + 5, y);
        cs.showText(truncate(label, 25) + ":");
        cs.newLineAtOffset(120, 0);
        cs.showText(truncate(value != null ? value : "—", 55));
        cs.endText();
        return y - LH;
    }

    private float tableHeaderRow(PDPageContentStream cs, PDType1Font bold, float y,
                                 String[] headers, float[] xPos) throws IOException {
        cs.setNonStrokingColor(0.93f, 0.93f, 0.93f);
        cs.addRect(ML - 5, y - 4, MR - ML + 10, LH + 4);
        cs.fill();
        cs.setNonStrokingColor(0f, 0f, 0f);
        cs.beginText();
        cs.setFont(bold, 9);
        for (int i = 0; i < headers.length; i++) {
            cs.newLineAtOffset(i == 0 ? xPos[0] : xPos[i] - xPos[i - 1], 0);
            cs.showText(headers[i]);
        }
        cs.endText();
        return y - LH - 4;
    }

    private float tableRow(PDPageContentStream cs, PDType1Font reg, float y,
                           String[] values, float[] xPos) throws IOException {
        cs.beginText();
        cs.setFont(reg, 9);
        cs.newLineAtOffset(xPos[0], y);
        for (int i = 0; i < values.length; i++) {
            if (i > 0) cs.newLineAtOffset(xPos[i] - xPos[i - 1], 0);
            cs.showText(truncate(values[i], i == 0 ? 15 : (i == 1 ? 30 : 40)));
        }
        cs.endText();
        return y - LH;
    }

    private float drawText(PDPageContentStream cs, PDType1Font font, float size, float x, float y, String text) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
        return y;
    }

    private void drawTextRight(PDPageContentStream cs, PDType1Font font, float size, float rightEdge, float y, String text) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        float w = font.getStringWidth(text) / 1000 * size;
        cs.newLineAtOffset(rightEdge - w, y);
        cs.showText(text);
        cs.endText();
    }

    private String formatEnum(String raw) {
        if (raw == null) return "—";
        return raw.replace('_', ' ');
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }
}
