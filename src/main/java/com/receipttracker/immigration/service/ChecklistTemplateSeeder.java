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
