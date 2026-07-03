package com.receipttracker.immigration.service;

import com.receipttracker.immigration.model.question.FormFieldEntry;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceEntry;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class PdfFieldApplierTest {

    private PDDocument doc;
    private PDPage page;
    private PDAcroForm acroForm;

    @BeforeEach
    void setUp() {
        doc = new PDDocument();
        page = new PDPage(PDRectangle.LETTER);
        doc.addPage(page);
        acroForm = new PDAcroForm(doc);
        doc.getDocumentCatalog().setAcroForm(acroForm);
        PDResources res = new PDResources();
        res.put(COSName.getPDFName("Helv"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
        acroForm.setDefaultResources(res);
    }

    @AfterEach
    void tearDown() throws IOException {
        doc.close();
    }

    private PDTextField addTextField(String name) throws IOException {
        PDTextField tf = new PDTextField(acroForm);
        tf.setPartialName(name);
        tf.setDefaultAppearance("/Helv 0 Tf 0 g");
        PDAnnotationWidget w = tf.getWidgets().get(0);
        w.setRectangle(new PDRectangle(50, 700, 200, 20));
        w.setPage(page);
        page.getAnnotations().add(w);
        acroForm.getFields().add(tf);
        return tf;
    }

    /** Checkbox whose AcroForm on-value is "1" (typical for USCIS forms). */
    private PDCheckBox addCheckBox(String name) throws IOException {
        PDCheckBox cb = new PDCheckBox(acroForm);
        cb.setPartialName(name);
        PDAnnotationWidget w = cb.getWidgets().get(0);
        w.setRectangle(new PDRectangle(50, 650, 15, 15));
        w.setPage(page);
        COSDictionary normalStates = new COSDictionary();
        normalStates.setItem(COSName.getPDFName("Off"), new PDAppearanceStream(doc));
        normalStates.setItem(COSName.getPDFName("1"), new PDAppearanceStream(doc));
        PDAppearanceDictionary ap = new PDAppearanceDictionary();
        ap.setNormalAppearance(new PDAppearanceEntry(normalStates));
        w.setAppearance(ap);
        page.getAnnotations().add(w);
        acroForm.getFields().add(cb);
        return cb;
    }

    private FormFieldEntry entry(String pdfFieldName, String type, String onValue,
                                 Map<String, String> valueMap) {
        FormFieldEntry e = new FormFieldEntry();
        e.setQuestionKey("test.key");
        e.setPdfFieldName(pdfFieldName);
        e.setPdfFieldType(type);
        e.setCheckboxOnValue(onValue);
        e.setValueMap(valueMap);
        return e;
    }

    // ── resolvePdfValue ───────────────────────────────────────────────────────

    @Test
    void resolvePdfValue_exactMatch() {
        FormFieldEntry e = entry("F", "text", null, Map.of("Male", "M", "Female", "F"));
        assertThat(PdfFieldApplier.resolvePdfValue(e, "Male")).isEqualTo("M");
    }

    @Test
    void resolvePdfValue_caseInsensitiveFallback() {
        FormFieldEntry e = entry("F", "text", null, Map.of("Male", "M"));
        assertThat(PdfFieldApplier.resolvePdfValue(e, "MALE")).isEqualTo("M");
    }

    @Test
    void resolvePdfValue_unmappedPassesThrough() {
        FormFieldEntry e = entry("F", "text", null, Map.of("Male", "M"));
        assertThat(PdfFieldApplier.resolvePdfValue(e, "Other")).isEqualTo("Other");
    }

    @Test
    void resolvePdfValue_noMapPassesThrough() {
        FormFieldEntry e = entry("F", "text", null, null);
        assertThat(PdfFieldApplier.resolvePdfValue(e, "anything")).isEqualTo("anything");
    }

    // ── isCheckboxOn ──────────────────────────────────────────────────────────

    @Test
    void isCheckboxOn_explicitOnValue_matchesCaseInsensitive() {
        assertThat(PdfFieldApplier.isCheckboxOn("Yes", "yes")).isTrue();
        assertThat(PdfFieldApplier.isCheckboxOn("No", "Yes")).isFalse();
    }

    @Test
    void isCheckboxOn_defaultTruthyHeuristic() {
        assertThat(PdfFieldApplier.isCheckboxOn("true", null)).isTrue();
        assertThat(PdfFieldApplier.isCheckboxOn("YES", null)).isTrue();
        assertThat(PdfFieldApplier.isCheckboxOn("1", null)).isTrue();
        assertThat(PdfFieldApplier.isCheckboxOn("false", null)).isFalse();
        assertThat(PdfFieldApplier.isCheckboxOn("No", null)).isFalse();
        assertThat(PdfFieldApplier.isCheckboxOn("", null)).isFalse();
        assertThat(PdfFieldApplier.isCheckboxOn(null, null)).isFalse();
    }

    // ── apply: text ───────────────────────────────────────────────────────────

    @Test
    void apply_textField_setsTranslatedValue() throws IOException {
        addTextField("Pt3Line2_Sex");
        FormFieldEntry e = entry("Pt3Line2_Sex", "text", null, Map.of("Male", "M"));

        boolean filled = PdfFieldApplier.apply(acroForm, e, "Male");

        assertThat(filled).isTrue();
        assertThat(acroForm.getField("Pt3Line2_Sex").getValueAsString()).isEqualTo("M");
    }

    @Test
    void apply_textField_defaultTypeWhenNull() throws IOException {
        addTextField("Pt1Line1_Name");
        FormFieldEntry e = entry("Pt1Line1_Name", null, null, null);

        boolean filled = PdfFieldApplier.apply(acroForm, e, "Acme Corp");

        assertThat(filled).isTrue();
        assertThat(acroForm.getField("Pt1Line1_Name").getValueAsString()).isEqualTo("Acme Corp");
    }

    // ── apply: checkbox ───────────────────────────────────────────────────────

    @Test
    void apply_checkbox_checksWhenValueMatchesOnValue() throws IOException {
        PDCheckBox cb = addCheckBox("Pt4Line2_Yes");
        FormFieldEntry e = entry("Pt4Line2_Yes", "checkbox", "Yes", null);

        boolean filled = PdfFieldApplier.apply(acroForm, e, "Yes");

        assertThat(filled).isTrue();
        assertThat(cb.isChecked()).isTrue();
        assertThat(cb.getValue()).isEqualTo("1"); // the PDF's own on-value
    }

    @Test
    void apply_checkbox_unchecksWhenValueDoesNotMatch() throws IOException {
        PDCheckBox cb = addCheckBox("Pt4Line2_No");
        cb.check(); // start checked to prove unCheck happens
        FormFieldEntry e = entry("Pt4Line2_No", "checkbox", "No", null);

        boolean filled = PdfFieldApplier.apply(acroForm, e, "Yes");

        assertThat(filled).isTrue();
        assertThat(cb.isChecked()).isFalse();
    }

    @Test
    void apply_checkbox_booleanQuestionWithTruthyDefault() throws IOException {
        PDCheckBox cb = addCheckBox("Pt1Line6_Nonprofit");
        FormFieldEntry e = entry("Pt1Line6_Nonprofit", "checkbox", null, null);

        PdfFieldApplier.apply(acroForm, e, "true");

        assertThat(cb.isChecked()).isTrue();
    }

    // ── apply: missing field ──────────────────────────────────────────────────

    @Test
    void apply_missingField_returnsFalse() {
        FormFieldEntry e = entry("DoesNotExist", "text", null, null);
        assertThat(PdfFieldApplier.apply(acroForm, e, "x")).isFalse();
    }
}
