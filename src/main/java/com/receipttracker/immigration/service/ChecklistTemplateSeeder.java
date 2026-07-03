package com.receipttracker.immigration.service;

import com.receipttracker.immigration.model.ChecklistTemplate;
import com.receipttracker.immigration.repository.ChecklistTemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Seeds default evidence checklist templates on first startup.
 * Runs only when the table is empty — safe to restart in any environment.
 */
@Component
public class ChecklistTemplateSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ChecklistTemplateSeeder.class);

    @Autowired private ChecklistTemplateRepository repo;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (repo.count() > 0) return;
        log.info("Seeding checklist templates…");
        List<ChecklistTemplate> all = new ArrayList<>();
        all.addAll(i129Templates());
        all.addAll(i485Templates());
        all.addAll(i140Templates());
        all.addAll(permTemplates());
        all.addAll(i765Templates());
        all.addAll(i131Templates());
        all.addAll(i539Templates());
        all.addAll(i290bTemplates());
        all.addAll(i693Templates());
        repo.saveAll(all);
        log.info("Seeded {} checklist templates", all.size());
    }

    // ── I-129 (H-1B Petition) ────────────────────────────────────────────────

    private List<ChecklistTemplate> i129Templates() {
        String ft = "I129";
        return List.of(
            t(ft, "PASSPORT_COPY",              "Personal Documents", "Passport (copy, all pages)",              true,  null, 10),
            t(ft, "I94_RECORD",                 "Personal Documents", "I-94 Arrival/Departure Record",           true,  null, 20),
            t(ft, "PRIOR_VISA_STAMPS",          "Personal Documents", "Prior U.S. Visa Stamps (copies)",         false, null, 30),
            t(ft, "DEGREE_CERTIFICATE",         "Academic & Professional", "Degree Certificate(s)",              true,  null, 40),
            t(ft, "TRANSCRIPTS",                "Academic & Professional", "Official Transcripts",                true,  null, 50),
            t(ft, "RESUME_CV",                  "Academic & Professional", "Current Resume / CV",                 true,  null, 60),
            t(ft, "CREDENTIALS_EVAL",           "Academic & Professional", "Foreign Credential Evaluation (if applicable)", false, null, 70),
            t(ft, "SUPPORT_LETTER",             "Employer Documents", "Employer Support Letter",                 true,  null, 80),
            t(ft, "LCA_CERTIFIED",              "Employer Documents", "Labor Condition Application (certified)", true,  null, 90),
            t(ft, "ORG_CHART",                  "Employer Documents", "Organizational Chart",                   true,  null, 100),
            t(ft, "SPECIALTY_OCC_EVIDENCE",     "Employer Documents", "Evidence of Specialty Occupation",       true,  null, 110),
            t(ft, "EMPLOYER_TAX_RETURNS",       "Financial",         "Employer Tax Returns (last 2 years)",     true,  null, 120),
            t(ft, "FINANCIAL_STATEMENTS",       "Financial",         "Employer Financial Statements",           false, null, 130)
        );
    }

    // ── I-485 (Adjustment of Status) ─────────────────────────────────────────

    private List<ChecklistTemplate> i485Templates() {
        String ft = "I485";
        String i140Cond = "{\"i140Approved\":true}";
        return List.of(
            t(ft, "PASSPORT_COPY",      "Personal Documents", "Passport (copy, valid or expired)",               true,  null,      10),
            t(ft, "I94_RECORD",         "Personal Documents", "I-94 Arrival/Departure Record",                   true,  null,      20),
            t(ft, "BIRTH_CERTIFICATE",  "Personal Documents", "Birth Certificate (with translation if needed)",  true,  null,      30),
            t(ft, "PHOTOS_2X2",         "Personal Documents", "2×2 Passport Photos (2 copies)",                  true,  null,      40),
            t(ft, "MARRIAGE_CERT",      "Personal Documents", "Marriage Certificate (if applicable)",             false, null,      50),
            t(ft, "PRIOR_VISA_STAMPS",  "Immigration History","Prior U.S. Visa Stamps",                          true,  null,      60),
            t(ft, "I140_APPROVAL",      "Immigration History","I-140 Approval Notice",                            true,  i140Cond,  70),
            t(ft, "I693_MEDICAL",       "Medical",           "I-693 Medical Examination (sealed envelope)",      true,  null,      80),
            t(ft, "I864_AFFIDAVIT",     "Financial",         "I-864 Affidavit of Support",                       true,  null,      90),
            t(ft, "SPONSOR_TAX_RETURNS","Financial",         "Sponsor Tax Returns (last 3 years)",               true,  null,     100),
            t(ft, "EMPLOYMENT_EVIDENCE","Financial",         "Evidence of Sponsor's Employment",                 true,  null,     110)
        );
    }

    // ── I-140 EB-2 / EB-3 ────────────────────────────────────────────────────

    private List<ChecklistTemplate> i140Templates() {
        // Same items apply to both I140_EB2 and I140_EB3 — seed once per form type
        List<ChecklistTemplate> all = new ArrayList<>();
        for (String ft : List.of("I140_EB2", "I140_EB3")) {
            all.addAll(List.of(
                t(ft, "DEGREE_CERTIFICATE",  "Beneficiary Qualifications", "Degree Certificate(s)",                 true,  null, 10),
                t(ft, "TRANSCRIPTS",         "Beneficiary Qualifications", "Official Transcripts",                  true,  null, 20),
                t(ft, "CREDENTIALS_EVAL",    "Beneficiary Qualifications", "Foreign Credential Evaluation",         false, null, 30),
                t(ft, "RESUME_CV",           "Beneficiary Qualifications", "Current Resume / CV",                   true,  null, 40),
                t(ft, "RECOMMENDATION_LTRS", "Beneficiary Qualifications", "Recommendation Letters (3 minimum)",   true,  null, 50),
                t(ft, "SUPPORT_LETTER",      "Employer Documents",         "Employer Support / Offer Letter",       true,  null, 60),
                t(ft, "ORG_CHART",           "Employer Documents",         "Organizational Chart",                  false, null, 70),
                t(ft, "EMPLOYER_TAX_RETURNS","Financial",                  "Employer Tax Returns (last 3 years)",   true,  null, 80),
                t(ft, "FINANCIAL_STATEMENTS","Financial",                  "Employer Financial Statements",         false, null, 90)
            ));
        }
        return all;
    }

    // ── PERM ─────────────────────────────────────────────────────────────────

    private List<ChecklistTemplate> permTemplates() {
        String ft = "PERM";
        return List.of(
            t(ft, "JOB_POSTING",         "Recruitment",    "Newspaper Job Posting (Sunday edition)",              true,  null, 10),
            t(ft, "ONLINE_JOB_AD",       "Recruitment",    "Online Job Advertisement",                            true,  null, 20),
            t(ft, "RESUMES_RECEIVED",    "Recruitment",    "All Resumes Received",                                true,  null, 30),
            t(ft, "INTERVIEW_NOTES",     "Recruitment",    "Interview / Evaluation Notes",                        true,  null, 40),
            t(ft, "REJECTION_REASONS",   "Recruitment",    "U.S. Worker Rejection Reasons (documented)",          true,  null, 50),
            t(ft, "PWD",                 "Wage & Position","Prevailing Wage Determination (from DOL)",            true,  null, 60),
            t(ft, "JOB_DESCRIPTION",     "Wage & Position","Detailed Job Description",                            true,  null, 70),
            t(ft, "RECRUITMENT_REPORT",  "Wage & Position","Recruitment Report Summary",                          true,  null, 80)
        );
    }

    // ── I-765 (Application for Employment Authorization) ────────────────────

    private List<ChecklistTemplate> i765Templates() {
        String ft = "I765";
        String h4Cond = "{\"caseTypeIn\":[\"H4_EAD\"]}";
        String gcCond = "{\"caseTypeIn\":[\"GC_EAD\"]}";
        String i140Cond = "{\"i140Approved\":true}";
        return List.of(
            t(ft, "PASSPORT_COPY",     "Personal Documents",         "Passport (copy, photo page)",                       true,  null,     10),
            t(ft, "PASSPORT_PHOTOS",   "Personal Documents",         "2×2 Passport-Style Photos (2 copies)",              true,  null,     20),
            t(ft, "I94_RECORD",        "Personal Documents",         "I-94 Arrival/Departure Record",                     true,  null,     30),
            t(ft, "PRIOR_EAD_COPY",    "Personal Documents",         "Copy of Previous EAD Card (front and back, if renewing/replacing)", false, null, 40),
            t(ft, "H1B_APPROVAL_NOTICE","Category-Specific Evidence","Spouse's Form I-797 Approval Notice for Form I-129 (H-1B)", true,  h4Cond,   50),
            t(ft, "MARRIAGE_CERT",     "Category-Specific Evidence", "Marriage Certificate",                              true,  h4Cond,   60),
            t(ft, "I140_APPROVAL",     "Category-Specific Evidence", "Form I-797 Approval Notice for I-140",              true,  i140Cond, 70),
            t(ft, "I485_RECEIPT",      "Category-Specific Evidence", "Form I-797 Receipt Notice for Pending I-485",       false, gcCond,   80)
        );
    }

    // ── I-131 (Reentry Permit / Advance Parole Document) ────────────────────

    private List<ChecklistTemplate> i131Templates() {
        String ft = "I131";
        String i485Cond = "{\"caseTypeIn\":[\"I485\"]}";
        String gcRenewalCond = "{\"caseTypeIn\":[\"GC_RENEWAL\"]}";
        return List.of(
            t(ft, "PASSPORT_COPY",         "Personal Documents",         "Passport (copy, photo page)",                              true,  null,          10),
            t(ft, "PASSPORT_PHOTOS",       "Personal Documents",         "2×2 Passport-Style Photos (2 copies)",                     true,  null,          20),
            t(ft, "PRIOR_TRAVEL_DOC_COPY", "Personal Documents",         "Copy of Previously Issued Reentry Permit or Advance Parole Document (if renewing/replacing)", false, null, 30),
            t(ft, "I485_RECEIPT_NOTICE",   "Category-Specific Evidence", "Form I-797 Receipt Notice for Pending I-485",              true,  i485Cond,      40),
            t(ft, "GREEN_CARD_COPY",       "Personal Documents",         "Permanent Resident Card (copy, front and back)",           true,  gcRenewalCond, 50),
            t(ft, "TRAVEL_NEED_EXPLANATION","Category-Specific Evidence","Explanation/Evidence Supporting Need for Travel",          false, null,          60)
        );
    }

    // ── I-539 (Application to Extend/Change Nonimmigrant Status) ────────────

    private List<ChecklistTemplate> i539Templates() {
        String ft = "I539";
        String h4Cond = "{\"caseTypeIn\":[\"H4\"]}";
        return List.of(
            t(ft, "PASSPORT_COPY",           "Personal Documents",         "Passport (copy, photo page)",                          true,  null,   10),
            t(ft, "I94_RECORD",              "Personal Documents",         "I-94 Arrival/Departure Record",                        true,  null,   20),
            t(ft, "H1B_APPROVAL_NOTICE",     "Category-Specific Evidence", "Principal H-1B Holder's Form I-797 Approval Notice",   true,  h4Cond, 30),
            t(ft, "MARRIAGE_CERT",           "Category-Specific Evidence", "Marriage Certificate (proof of relationship to principal)", true, h4Cond, 40),
            t(ft, "PRIOR_STATUS_DOC",        "Personal Documents",         "Evidence of Current/Prior Status (e.g. prior EAD, I-797, or visa stamp)", false, null, 50),
            t(ft, "FINANCIAL_SUPPORT_EVIDENCE","Category-Specific Evidence","Evidence of Financial Support (if not employed since last admission)", false, null, 60)
        );
    }

    // ── I-290B (Notice of Appeal or Motion) ──────────────────────────────────

    private List<ChecklistTemplate> i290bTemplates() {
        String ft = "I290B";
        return List.of(
            t(ft, "UNFAVORABLE_DECISION_NOTICE", "Category-Specific Evidence", "Copy of the Unfavorable Decision Notice Being Appealed", true,  null, 10),
            t(ft, "BRIEF_OR_EVIDENCE",           "Category-Specific Evidence", "Brief and/or Supporting Evidence",                       false, null, 20),
            t(ft, "NEW_EVIDENCE_FOR_REOPEN",     "Category-Specific Evidence", "New Factual Evidence Supporting a Motion to Reopen",     false, null, 30),
            t(ft, "PRECEDENT_DECISIONS",         "Category-Specific Evidence", "Pertinent Precedent Decisions Supporting a Motion to Reconsider", false, null, 40)
        );
    }

    // ── I-693 (Report of Immigration Medical Examination and Vaccination Record) ──

    private List<ChecklistTemplate> i693Templates() {
        String ft = "I693";
        return List.of(
            t(ft, "COMPLETED_I693_FORM",   "Category-Specific Evidence", "Completed and Signed Form I-693 from the Civil Surgeon (sealed envelope, if required)", true,  null, 10),
            t(ft, "VACCINATION_RECORDS",   "Personal Documents",         "Prior Vaccination Records Brought to the Exam",                                       false, null, 20),
            t(ft, "PRIOR_MEDICAL_RECORDS", "Personal Documents",         "Relevant Prior Medical Records (if applicable)",                                      false, null, 30)
        );
    }

    // ── Builder helper ───────────────────────────────────────────────────────

    private ChecklistTemplate t(String formType, String itemKey, String category,
                                  String label, boolean required,
                                  String conditionRule, int sortOrder) {
        ChecklistTemplate ct = new ChecklistTemplate();
        ct.setFormType(formType);
        ct.setItemKey(formType + "_" + itemKey); // scoped key prevents cross-type collisions
        ct.setCategory(category);
        ct.setLabel(label);
        ct.setRequired(required);
        ct.setConditionRule(conditionRule);
        ct.setSortOrder(sortOrder);
        return ct;
    }
}
