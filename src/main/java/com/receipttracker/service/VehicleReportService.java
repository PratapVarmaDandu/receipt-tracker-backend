package com.receipttracker.service;

import com.receipttracker.model.FuelRecord;
import com.receipttracker.model.MaintenanceRecord;
import com.receipttracker.model.Vehicle;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Generates a professional PDF "Vehicle for Sale" report using Apache PDFBox 3.x.
 * Covers: vehicle specs, registration, insurance, full service history,
 * fuel economy stats, and open recalls.
 */
@Service
public class VehicleReportService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy");
    private static final float MARGIN_LEFT   = 50f;
    private static final float MARGIN_RIGHT  = 545f;
    private static final float PAGE_TOP      = 762f;
    private static final float PAGE_BOTTOM   = 50f;
    private static final float LINE_HEIGHT   = 14f;

    public byte[] generateSaleReport(
            Vehicle vehicle,
            List<MaintenanceRecord> maintenance,
            List<FuelRecord> fuelRecords,
            List<Map<String, String>> recalls,
            BigDecimal totalMaintenanceCost,
            Double averageMpg
    ) throws IOException {

        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            PDType1Font fontBold   = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font fontReg    = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font fontSmall  = new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);

            // ── Page 1: Vehicle overview + registration + insurance ─────────────

            PDPage page1 = new PDPage(PDRectangle.LETTER);
            doc.addPage(page1);
            float y = PAGE_TOP;

            try (PDPageContentStream cs = new PDPageContentStream(doc, page1)) {
                // Header bar
                cs.setNonStrokingColor(0.31f, 0.27f, 0.90f);
                cs.addRect(MARGIN_LEFT - 10, y - 5, MARGIN_RIGHT - MARGIN_LEFT + 20, 40);
                cs.fill();

                // Title
                cs.setNonStrokingColor(1f, 1f, 1f);
                cs.beginText();
                cs.setFont(fontBold, 16);
                cs.newLineAtOffset(MARGIN_LEFT, y + 12);
                cs.showText("Vehicle History & Sale Report");
                cs.endText();

                cs.setNonStrokingColor(0.5f, 0.5f, 0.5f);
                cs.beginText();
                cs.setFont(fontReg, 9);
                cs.newLineAtOffset(MARGIN_RIGHT - 120, y + 12);
                cs.showText("Generated " + LocalDate.now().format(DATE_FMT));
                cs.endText();

                y -= 55;
                cs.setNonStrokingColor(0f, 0f, 0f);

                // Vehicle title
                y = drawText(cs, fontBold, 20, MARGIN_LEFT, y,
                        vehicle.getModelYear() + " " + vehicle.getMake() + " " + vehicle.getModel()
                        + (vehicle.getTrim() != null ? " " + vehicle.getTrim() : ""));
                y -= 4;

                if (vehicle.getVin() != null) {
                    y = drawText(cs, fontReg, 9, MARGIN_LEFT, y, "VIN: " + vehicle.getVin());
                }
                y -= 12;

                // Two-column specs grid
                y = sectionHeader(cs, fontBold, "Vehicle Details", y);

                y = twoCol(cs, fontBold, fontReg, "Make", vehicle.getMake(),
                        "Model", vehicle.getModel(), y);
                y = twoCol(cs, fontBold, fontReg, "Year", String.valueOf(vehicle.getModelYear()),
                        "Color", orDash(vehicle.getColor()), y);
                y = twoCol(cs, fontBold, fontReg, "Trim", orDash(vehicle.getTrim()),
                        "Current Mileage", vehicle.getCurrentMileage() != null
                                ? vehicle.getCurrentMileage() + " mi" : "—", y);
                if (vehicle.getPurchaseDate() != null) {
                    y = twoCol(cs, fontBold, fontReg, "Purchased",
                            vehicle.getPurchaseDate().format(DATE_FMT),
                            "Purchase Price", vehicle.getPurchasePrice() != null
                                    ? "$" + vehicle.getPurchasePrice().setScale(2, RoundingMode.HALF_UP) : "—", y);
                }
                y -= 10;

                // Registration
                y = sectionHeader(cs, fontBold, "Registration", y);
                y = twoCol(cs, fontBold, fontReg, "License Plate",
                        orDash(vehicle.getLicensePlate()) + " (" + orDash(vehicle.getRegistrationState()) + ")",
                        "Tag Expires", vehicle.getTagExpirationDate() != null
                                ? vehicle.getTagExpirationDate().format(DATE_FMT) : "—", y);
                y -= 10;

                // Insurance
                y = sectionHeader(cs, fontBold, "Insurance", y);
                y = twoCol(cs, fontBold, fontReg, "Provider", orDash(vehicle.getInsuranceProvider()),
                        "Policy #", orDash(vehicle.getInsurancePolicyNumber()), y);
                if (vehicle.getInsuranceExpiryDate() != null) {
                    y = twoCol(cs, fontBold, fontReg, "Expires",
                            vehicle.getInsuranceExpiryDate().format(DATE_FMT),
                            "", "", y);
                }
                y -= 10;

                // Stats
                y = sectionHeader(cs, fontBold, "Ownership Summary", y);
                y = twoCol(cs, fontBold, fontReg, "Total Maintenance Cost",
                        totalMaintenanceCost != null ? "$" + totalMaintenanceCost.setScale(2, RoundingMode.HALF_UP) : "—",
                        "Avg Fuel Economy",
                        averageMpg != null ? String.format("%.1f MPG", averageMpg) : "—", y);
                y = twoCol(cs, fontBold, fontReg, "Maintenance Records",
                        String.valueOf(maintenance.size()),
                        "Fuel Records", String.valueOf(fuelRecords.size()), y);

                if (vehicle.getNotes() != null && !vehicle.getNotes().isBlank()) {
                    y -= 10;
                    y = sectionHeader(cs, fontBold, "Owner Notes", y);
                    for (String line : vehicle.getNotes().split("\n")) {
                        y = drawText(cs, fontReg, 9, MARGIN_LEFT, y, line);
                    }
                }
            }

            // ── Page 2: Maintenance history ─────────────────────────────────────

            if (!maintenance.isEmpty()) {
                PDPage page2 = new PDPage(PDRectangle.LETTER);
                doc.addPage(page2);
                float y2 = PAGE_TOP;

                try (PDPageContentStream cs = new PDPageContentStream(doc, page2)) {
                    y2 = drawText(cs, fontBold, 14, MARGIN_LEFT, y2, "Service & Maintenance History");
                    y2 -= 8;
                    y2 = tableHeader(cs, fontBold, y2, new float[]{60, 140, 80, 80, 185},
                            new String[]{"Date", "Service", "Mileage", "Cost", "Provider / Notes"});

                    boolean shade = false;
                    for (MaintenanceRecord r : maintenance) {
                        if (y2 < PAGE_BOTTOM + 20) {
                            // Continue on new page
                            cs.setNonStrokingColor(0f, 0f, 0f);
                            break;
                        }
                        if (shade) {
                            cs.setNonStrokingColor(0.96f, 0.96f, 0.98f);
                            cs.addRect(MARGIN_LEFT - 5, y2 - LINE_HEIGHT + 2, MARGIN_RIGHT - MARGIN_LEFT + 10, LINE_HEIGHT + 1);
                            cs.fill();
                        }
                        cs.setNonStrokingColor(0f, 0f, 0f);
                        float[] colX = {MARGIN_LEFT, MARGIN_LEFT + 65, MARGIN_LEFT + 205, MARGIN_LEFT + 285, MARGIN_LEFT + 365};
                        String label = r.getMaintenanceType().name().replace('_', ' ');
                        if (r.getCustomDescription() != null && !r.getCustomDescription().isBlank()) {
                            label = r.getCustomDescription();
                        }
                        String[] vals = {
                            r.getServiceDate().format(DATE_FMT),
                            truncate(label, 22),
                            r.getMileage() != null ? r.getMileage() + " mi" : "—",
                            r.getCost() != null ? "$" + r.getCost().setScale(2, RoundingMode.HALF_UP) : "—",
                            truncate(orDash(r.getProvider()) + (r.getNotes() != null && !r.getNotes().isBlank() ? " · " + r.getNotes() : ""), 30)
                        };
                        cs.beginText();
                        cs.setFont(fontReg, 8);
                        for (int i = 0; i < vals.length; i++) {
                            cs.newLineAtOffset(i == 0 ? colX[0] : colX[i] - colX[i-1], 0);
                            cs.showText(vals[i]);
                        }
                        cs.endText();
                        y2 -= LINE_HEIGHT;
                        shade = !shade;
                    }

                    // Footer
                    cs.setNonStrokingColor(0.6f, 0.6f, 0.6f);
                    cs.beginText();
                    cs.setFont(fontSmall, 7);
                    cs.newLineAtOffset(MARGIN_LEFT, PAGE_BOTTOM - 10);
                    cs.showText("This report was generated by Receipt Tracker. Verify all information before completing any sale.");
                    cs.endText();
                }
            }

            // ── Page 3: Fuel log + recalls ──────────────────────────────────────

            boolean needsPage3 = !fuelRecords.isEmpty() || !recalls.isEmpty();
            if (needsPage3) {
                PDPage page3 = new PDPage(PDRectangle.LETTER);
                doc.addPage(page3);
                float y3 = PAGE_TOP;

                try (PDPageContentStream cs = new PDPageContentStream(doc, page3)) {
                    if (!fuelRecords.isEmpty()) {
                        y3 = drawText(cs, fontBold, 14, MARGIN_LEFT, y3, "Fuel Log");
                        y3 -= 8;
                        y3 = tableHeader(cs, fontBold, y3, new float[]{75, 85, 60, 80, 80, 165},
                                new String[]{"Date", "Odometer", "Gallons", "$/Gal", "Total", "Station"});

                        for (FuelRecord f : fuelRecords) {
                            if (y3 < PAGE_BOTTOM + 20) break;
                            float[] colX = {MARGIN_LEFT, MARGIN_LEFT+80, MARGIN_LEFT+165, MARGIN_LEFT+225, MARGIN_LEFT+305, MARGIN_LEFT+385};
                            cs.beginText();
                            cs.setFont(fontReg, 8);
                            String[] vals = {
                                f.getFillDate().format(DATE_FMT),
                                f.getOdometer() + " mi",
                                f.getGallons().setScale(2, RoundingMode.HALF_UP).toPlainString(),
                                f.getPricePerGallon() != null ? "$" + f.getPricePerGallon().setScale(3, RoundingMode.HALF_UP) : "—",
                                f.getTotalCost() != null ? "$" + f.getTotalCost().setScale(2, RoundingMode.HALF_UP) : "—",
                                truncate(orDash(f.getStationName()), 22)
                            };
                            for (int i = 0; i < vals.length; i++) {
                                cs.newLineAtOffset(i == 0 ? colX[0] : colX[i] - colX[i-1], 0);
                                cs.showText(vals[i]);
                            }
                            cs.endText();
                            y3 -= LINE_HEIGHT;
                        }
                        y3 -= 15;
                    }

                    if (!recalls.isEmpty()) {
                        y3 = sectionHeader(cs, fontBold, "NHTSA Safety Recalls", y3);
                        for (Map<String, String> recall : recalls) {
                            if (y3 < PAGE_BOTTOM + 30) break;
                            cs.setNonStrokingColor(0.85f, 0.05f, 0.05f);
                            y3 = drawText(cs, fontBold, 9, MARGIN_LEFT, y3,
                                    "Campaign: " + recall.getOrDefault("campaignNumber", "N/A")
                                    + "  —  " + recall.getOrDefault("component", ""));
                            cs.setNonStrokingColor(0f, 0f, 0f);
                            if (recall.get("summary") != null) {
                                y3 = drawWrappedText(cs, fontReg, 8, MARGIN_LEFT, y3,
                                        recall.get("summary"), 480);
                            }
                            y3 -= 6;
                        }
                    } else if (needsPage3) {
                        y3 = sectionHeader(cs, fontBold, "NHTSA Safety Recalls", y3);
                        y3 = drawText(cs, fontReg, 9, MARGIN_LEFT, y3, "No open recalls found for this vehicle.");
                    }

                    cs.setNonStrokingColor(0.6f, 0.6f, 0.6f);
                    cs.beginText();
                    cs.setFont(fontSmall, 7);
                    cs.newLineAtOffset(MARGIN_LEFT, PAGE_BOTTOM - 10);
                    cs.showText("Recall data sourced from NHTSA (api.nhtsa.gov). Generated " + LocalDate.now().format(DATE_FMT));
                    cs.endText();
                }
            }

            doc.save(out);
            return out.toByteArray();
        }
    }

    // ── Drawing helpers ───────────────────────────────────────────────────────

    private float drawText(PDPageContentStream cs, PDType1Font font, float size,
                           float x, float y, String text) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(text != null ? text : "");
        cs.endText();
        return y - LINE_HEIGHT;
    }

    private float drawWrappedText(PDPageContentStream cs, PDType1Font font, float size,
                                  float x, float y, String text, float maxWidth) throws IOException {
        if (text == null) return y;
        String[] words = text.split("\\s+");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            String test = line.isEmpty() ? word : line + " " + word;
            float w = font.getStringWidth(test) / 1000 * size;
            if (w > maxWidth && !line.isEmpty()) {
                y = drawText(cs, font, size, x, y, line.toString());
                line = new StringBuilder(word);
            } else {
                if (!line.isEmpty()) line.append(' ');
                line.append(word);
            }
        }
        if (!line.isEmpty()) y = drawText(cs, font, size, x, y, line.toString());
        return y;
    }

    private float sectionHeader(PDPageContentStream cs, PDType1Font fontBold,
                                String title, float y) throws IOException {
        cs.setNonStrokingColor(0.31f, 0.27f, 0.90f);
        cs.addRect(MARGIN_LEFT - 5, y - 3, MARGIN_RIGHT - MARGIN_LEFT + 10, 14);
        cs.fill();
        cs.setNonStrokingColor(1f, 1f, 1f);
        cs.beginText();
        cs.setFont(fontBold, 9);
        cs.newLineAtOffset(MARGIN_LEFT, y);
        cs.showText(title.toUpperCase());
        cs.endText();
        cs.setNonStrokingColor(0f, 0f, 0f);
        return y - 20;
    }

    private float twoCol(PDPageContentStream cs, PDType1Font fontBold, PDType1Font fontReg,
                         String l1, String v1, String l2, String v2, float y) throws IOException {
        float mid = (MARGIN_LEFT + MARGIN_RIGHT) / 2;
        cs.beginText();
        cs.setFont(fontBold, 8);
        cs.newLineAtOffset(MARGIN_LEFT, y);
        cs.showText(l1 + ": ");
        cs.setFont(fontReg, 8);
        cs.showText(v1 != null ? v1 : "—");
        if (l2 != null && !l2.isBlank()) {
            cs.newLineAtOffset(mid - MARGIN_LEFT, 0);
            cs.setFont(fontBold, 8);
            cs.showText(l2 + ": ");
            cs.setFont(fontReg, 8);
            cs.showText(v2 != null ? v2 : "—");
        }
        cs.endText();
        return y - LINE_HEIGHT;
    }

    private float tableHeader(PDPageContentStream cs, PDType1Font fontBold,
                              float y, float[] widths, String[] cols) throws IOException {
        cs.setNonStrokingColor(0.2f, 0.2f, 0.2f);
        cs.addRect(MARGIN_LEFT - 5, y - 3, MARGIN_RIGHT - MARGIN_LEFT + 10, 14);
        cs.fill();
        cs.setNonStrokingColor(1f, 1f, 1f);
        cs.beginText();
        cs.setFont(fontBold, 8);
        float x = MARGIN_LEFT;
        for (int i = 0; i < cols.length; i++) {
            cs.newLineAtOffset(i == 0 ? x : widths[i - 1], 0);
            cs.showText(cols[i]);
        }
        cs.endText();
        cs.setNonStrokingColor(0f, 0f, 0f);
        return y - 18;
    }

    private String orDash(String s) { return (s != null && !s.isBlank()) ? s : "—"; }

    private String truncate(String s, int max) {
        if (s == null) return "—";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
