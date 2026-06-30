package com.receipttracker.immigration.service;

import com.receipttracker.immigration.model.question.CanonicalQuestion;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Builds a static, fully-fillable AcroForm PDF ("data sheet") for a USCIS form type — one
 * labelled text field per canonical question, the field named exactly as the mapping expects.
 * Unlike the dynamic XFA PDFs USCIS publishes, this renders and flattens correctly everywhere
 * and PDFBox can fill it reliably. The team can later replace it with a pixel-perfect replica
 * that reuses the same field names without touching the fill pipeline.
 */
@Service
public class StaticFormTemplateService {

    private static final float MARGIN = 50f;
    private static final float LABEL_SIZE = 9f;
    private static final float SECTION_SIZE = 11f;

    /**
     * @param fieldNames questionKey → AcroForm field name (must match the saved mapping)
     */
    public byte[] build(String formType, String editionDate,
                        List<CanonicalQuestion> questions, Map<String, String> fieldNames) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDAcroForm acroForm = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(acroForm);

            PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDResources dr = new PDResources();
            dr.put(COSName.getPDFName("Helv"), font);
            acroForm.setDefaultResources(dr);
            acroForm.setDefaultAppearance("/Helv 9 Tf 0 g");

            float pageW = PDRectangle.LETTER.getWidth();
            float pageH = PDRectangle.LETTER.getHeight();
            float top = pageH - MARGIN;
            float bottom = MARGIN + 20;
            float fieldW = pageW - 2 * MARGIN;

            PDPage page = addPage(doc);
            PDPageContentStream cs = new PDPageContentStream(doc, page);
            float y = top;

            // Title
            cs.beginText();
            cs.setFont(bold, 14);
            cs.newLineAtOffset(MARGIN, y);
            cs.showText(ascii(formType + " — Data Sheet (edition " + editionDate + ")"));
            cs.endText();
            y -= 30;

            String currentSection = null;
            for (CanonicalQuestion q : questions) {
                String fieldName = fieldNames.get(q.getKey());
                if (fieldName == null) continue;

                // Section header when the friendly section changes
                String section = q.getFriendlySection();
                if (section != null && !section.equals(currentSection)) {
                    if (y < bottom + 60) { cs.close(); page = addPage(doc); cs = new PDPageContentStream(doc, page); y = top; }
                    currentSection = section;
                    cs.beginText();
                    cs.setFont(bold, SECTION_SIZE);
                    cs.newLineAtOffset(MARGIN, y);
                    cs.showText(ascii(CanonicalQuestionRegistry.sectionLabel(section)));
                    cs.endText();
                    y -= 20;
                }

                if (y < bottom + 40) { cs.close(); page = addPage(doc); cs = new PDPageContentStream(doc, page); y = top; }

                // Label
                cs.beginText();
                cs.setFont(font, LABEL_SIZE);
                cs.newLineAtOffset(MARGIN, y);
                cs.showText(ascii(truncate(q.getLabel(), 95)));
                cs.endText();
                y -= 14;

                // Text field
                PDTextField field = new PDTextField(acroForm);
                field.setPartialName(fieldName);
                field.setDefaultAppearance("/Helv 9 Tf 0 g");
                acroForm.getFields().add(field);

                PDAnnotationWidget widget = field.getWidgets().get(0);
                widget.setRectangle(new PDRectangle(MARGIN, y - 2, fieldW, 14));
                widget.setPage(page);
                widget.setPrinted(true);
                page.getAnnotations().add(widget);

                y -= 26;
            }

            cs.close();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private PDPage addPage(PDDocument doc) {
        PDPage page = new PDPage(PDRectangle.LETTER);
        doc.addPage(page);
        return page;
    }

    /** Standard-14 Helvetica only encodes WinAnsi; drop anything outside printable ASCII. */
    private String ascii(String s) {
        if (s == null) return "";
        return s.replaceAll("[^\\x20-\\x7E]", " ");
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
