package com.receipttracker.immigration.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.receipttracker.immigration.dto.*;
import com.receipttracker.immigration.model.*;
import com.receipttracker.immigration.model.question.CanonicalQuestion;
import com.receipttracker.immigration.model.question.ResolvedValue;
import com.receipttracker.immigration.repository.*;
import com.receipttracker.model.User;
import com.receipttracker.repository.UserRepository;
import com.receipttracker.service.EmailService;
import com.receipttracker.service.EncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class FilingPackageService {

    private static final Logger log = LoggerFactory.getLogger(FilingPackageService.class);

    @Value("${app.frontend.url:http://localhost:4200}")
    private String frontendUrl;

    @Autowired private FilingPackageRepository packageRepo;
    @Autowired private FilingPackageQuestionnaireRepository questionnaireRepo;
    @Autowired private FilingPackageAnswerRepository answerRepo;
    @Autowired private ImmigrationCaseRepository caseRepo;
    @Autowired private ImmOrgRepository orgRepo;
    @Autowired private ImmOrgMemberRepository orgMemberRepo;
    @Autowired private AttorneyProfileRepository attorneyProfileRepo;
    @Autowired private CanonicalProfileRepository canonicalProfileRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private PermissionService permissionService;
    @Autowired private CanonicalQuestionRegistry questionRegistry;
    @Autowired private DataResolver dataResolver;
    @Autowired private EncryptionService encryptionService;
    @Autowired private EmailService emailService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AuditService auditService;

    // ── User resolution ───────────────────────────────────────────────────────

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        OAuth2User principal = (OAuth2User) auth.getPrincipal();
        String googleId = principal.getAttribute("sub");
        return userRepo.findByGoogleId(googleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }

    // ── Create package ────────────────────────────────────────────────────────

    @Transactional
    public FilingPackageDTO create(Long caseId, CreatePackageRequest req) {
        User caller = currentUser();
        permissionService.requireAccess(caller, caseId, GrantScope.MANAGE_CHECKLISTS);

        if (req.name() == null || req.name().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Package name is required");
        if (req.selectedFormTypes() == null || req.selectedFormTypes().isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one form type is required");

        ImmigrationCase immCase = caseRepo.findById(caseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found"));

        // Create the package entity
        FilingPackage pkg = new FilingPackage();
        pkg.setCaseId(caseId);
        pkg.setName(req.name());
        pkg.setSelectedFormTypesJson(toJson(req.selectedFormTypes()));
        pkg.setStatus("DRAFT");
        pkg.setCreatedByUserId(caller.getId());
        pkg = packageRepo.save(pkg);

        // Load questions for the selected forms
        List<CanonicalQuestion> questions = questionRegistry.getQuestionsForForms(req.selectedFormTypes());

        // Build resolution context
        DataResolver.ResolutionContext ctx = buildContext(immCase);

        // Prefill answers from profile/org
        Map<String, ResolvedValue> resolved = dataResolver.resolveAll(questions, ctx);
        for (CanonicalQuestion q : questions) {
            ResolvedValue rv = resolved.getOrDefault(q.getKey(), ResolvedValue.none());
            if (rv.hasValue()) {
                FilingPackageAnswer ans = new FilingPackageAnswer();
                ans.setFilingPackage(pkg);
                ans.setQuestionKey(q.getKey());
                ans.setOwner(q.getOwner());
                ans.setSource(rv.source());

                boolean sensitive = q.isEncrypt();
                if (sensitive) {
                    // Store encrypted; keep plaintext hash for comparison
                    ans.setValueJson(encryptionService.encrypt(rv.value()));
                    ans.setValueHash(sha256(rv.value()));
                } else {
                    ans.setValueJson(rv.value());
                }
                answerRepo.save(ans);
            }
        }

        // Create one questionnaire per owner that has questions
        Map<String, List<CanonicalQuestion>> byOwner = questionRegistry.getQuestionsByOwner(questions);
        List<FilingPackageQuestionnaire> questionnaires = new ArrayList<>();
        for (Map.Entry<String, List<CanonicalQuestion>> entry : byOwner.entrySet()) {
            String owner = entry.getKey();
            List<String> keys = entry.getValue().stream().map(CanonicalQuestion::getKey).toList();

            FilingPackageQuestionnaire q = new FilingPackageQuestionnaire();
            q.setFilingPackage(pkg);
            q.setTargetRelationship(owner);
            q.setToken(UUID.randomUUID().toString());
            q.setQuestionnaireSpecJson(toJson(keys));
            q.setStatus("PENDING");
            q.setExpiresAt(LocalDateTime.now().plusDays(30));
            questionnaires.add(questionnaireRepo.save(q));
        }

        return toDTO(pkg, questionnaires, questions, resolved);
    }

    // ── List packages for a case ──────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<FilingPackageDTO> listForCase(Long caseId) {
        User caller = currentUser();
        permissionService.requireAccess(caller, caseId, GrantScope.READ_CASE);

        return packageRepo.findByCaseIdOrderByCreatedAtDesc(caseId).stream()
                .map(pkg -> {
                    List<FilingPackageQuestionnaire> qs = questionnaireRepo.findByFilingPackageId(pkg.getId());
                    List<String> formTypes = parseFormTypes(pkg.getSelectedFormTypesJson());
                    List<CanonicalQuestion> questions = questionRegistry.getQuestionsForForms(formTypes);
                    List<FilingPackageAnswer> answers = answerRepo.findByFilingPackageId(pkg.getId());
                    Map<String, ResolvedValue> resolved = answersToResolvedMap(answers);
                    return toDTO(pkg, qs, questions, resolved);
                })
                .toList();
    }

    // ── Get single package ────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public FilingPackageDTO getPackage(Long caseId, Long packageId) {
        User caller = currentUser();
        permissionService.requireAccess(caller, caseId, GrantScope.READ_CASE);

        FilingPackage pkg = packageRepo.findByIdAndCaseId(packageId, caseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Package not found"));

        List<FilingPackageQuestionnaire> qs = questionnaireRepo.findByFilingPackageId(pkg.getId());
        List<String> formTypes = parseFormTypes(pkg.getSelectedFormTypesJson());
        List<CanonicalQuestion> questions = questionRegistry.getQuestionsForForms(formTypes);
        List<FilingPackageAnswer> answers = answerRepo.findByFilingPackageId(pkg.getId());
        Map<String, ResolvedValue> resolved = answersToResolvedMap(answers);
        return toDTO(pkg, qs, questions, resolved);
    }

    // ── Send questionnaires ───────────────────────────────────────────────────

    @Transactional
    public FilingPackageDTO sendQuestionnaires(Long caseId, Long packageId) {
        User caller = currentUser();
        permissionService.requireAccess(caller, caseId, GrantScope.MANAGE_CHECKLISTS);

        FilingPackage pkg = packageRepo.findByIdAndCaseId(packageId, caseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Package not found"));

        ImmigrationCase immCase = caseRepo.findById(caseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found"));

        List<FilingPackageQuestionnaire> qs = questionnaireRepo.findByFilingPackageId(pkg.getId());
        final String pkgName = pkg.getName(); // effectively final for lambda capture

        for (FilingPackageQuestionnaire q : qs) {
            if ("SUBMITTED".equals(q.getStatus())) continue;

            String link = frontendUrl + "/immigration/packages/questionnaire/" + q.getToken();

            switch (q.getTargetRelationship()) {
                case "BENEFICIARY" -> {
                    String email = immCase.getBeneficiary().getUser().getEmail();
                    String name  = immCase.getBeneficiary().getUser().getName();
                    emailService.sendSimpleEmail(email,
                            "Filing package questionnaire — " + pkgName,
                            "Hi " + name + ",\n\nYour attorney has requested information for the filing package \""
                            + pkgName + "\".\n\nPlease complete the questionnaire:\n" + link
                            + "\n\nThis link expires in 30 days.");
                    log.info("Questionnaire email sent to beneficiary {}", email);
                }
                case "EMPLOYER" -> {
                    if (immCase.getEmployerImmOrgId() != null) {
                        orgRepo.findById(immCase.getEmployerImmOrgId()).ifPresent(org -> {
                            String email = org.getContactEmail();
                            if (email != null && !email.isBlank()) {
                                emailService.sendSimpleEmail(email,
                                        "Filing package questionnaire — " + pkgName,
                                        "Please complete the employer questionnaire:\n" + link
                                        + "\n\nThis link expires in 30 days.");
                                log.info("Questionnaire email sent to employer {}", email);
                            }
                        });
                    }
                }
                case "ATTORNEY" -> {
                    if (immCase.getAssignedAttorneyMemberId() != null) {
                        orgMemberRepo.findById(immCase.getAssignedAttorneyMemberId()).ifPresent(member ->
                            emailService.sendSimpleEmail(member.getEmail(),
                                    "Filing package attorney section — " + pkgName,
                                    "Please complete the attorney section:\n" + link
                                    + "\n\nThis link expires in 30 days.")
                        );
                    }
                }
            }
        }

        pkg.setStatus("QUESTIONNAIRES_SENT");
        pkg = packageRepo.save(pkg);

        List<String> formTypes = parseFormTypes(pkg.getSelectedFormTypesJson());
        List<CanonicalQuestion> questions = questionRegistry.getQuestionsForForms(formTypes);
        List<FilingPackageAnswer> answers = answerRepo.findByFilingPackageId(pkg.getId());
        return toDTO(pkg, qs, questions, answersToResolvedMap(answers));
    }

    // ── Public questionnaire GET (no auth) ────────────────────────────────────

    @Transactional(readOnly = true)
    public QuestionnairePublicDTO getPublicQuestionnaire(String token) {
        FilingPackageQuestionnaire q = questionnaireRepo.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Questionnaire not found"));

        if ("EXPIRED".equals(q.getStatus()) || LocalDateTime.now().isAfter(q.getExpiresAt())) {
            throw new ResponseStatusException(HttpStatus.GONE, "This questionnaire link has expired");
        }

        FilingPackage pkg = q.getFilingPackage();
        List<String> formTypes = parseFormTypes(pkg.getSelectedFormTypesJson());
        List<String> questionKeys = parseStringList(q.getQuestionnaireSpecJson());

        // Load current prefill answers from DB
        Map<String, FilingPackageAnswer> answerByKey = answerRepo.findByFilingPackageId(pkg.getId())
                .stream().collect(Collectors.toMap(FilingPackageAnswer::getQuestionKey, a -> a, (a, b) -> a));

        // Build section → question list, ordered
        Map<String, List<QuestionnairePublicDTO.QuestionnaireQuestion>> sectionQuestions = new LinkedHashMap<>();

        for (String key : questionKeys) {
            Optional<CanonicalQuestion> cqOpt = questionRegistry.findByKey(key);
            if (cqOpt.isEmpty()) continue;
            CanonicalQuestion cq = cqOpt.get();

            boolean sensitive = cq.isEncrypt();
            FilingPackageAnswer ans = answerByKey.get(key);

            String prefillValue = null;
            String prefillSource = "none";
            if (ans != null && ans.getValueJson() != null && !ans.getValueJson().isBlank()) {
                prefillSource = ans.getSource() != null ? ans.getSource() : "none";
                if (!sensitive) {
                    // Non-sensitive: return plaintext prefill value
                    prefillValue = ans.getValueJson();
                }
                // Sensitive: always null (must re-enter)
            }

            List<String> options = null;
            if (cq.getValidation() != null && cq.getValidation().containsKey("options")) {
                Object opts = cq.getValidation().get("options");
                if (opts instanceof List<?> list) {
                    options = list.stream().map(Object::toString).toList();
                }
            }

            QuestionnairePublicDTO.QuestionnaireQuestion qqDTO =
                    new QuestionnairePublicDTO.QuestionnaireQuestion(
                            cq.getKey(), cq.getLabel(), cq.getSublabel(),
                            cq.getType(), cq.isRequired(),
                            cq.getValidation(), options,
                            prefillValue, prefillSource
                    );

            String section = cq.getFriendlySection() != null ? cq.getFriendlySection() : "other";
            sectionQuestions.computeIfAbsent(section, k -> new ArrayList<>()).add(qqDTO);
        }

        List<QuestionnairePublicDTO.QuestionnaireSection> sections = sectionQuestions.entrySet().stream()
                .map(e -> new QuestionnairePublicDTO.QuestionnaireSection(
                        e.getKey(),
                        CanonicalQuestionRegistry.sectionLabel(e.getKey()),
                        e.getValue()
                ))
                .toList();

        return new QuestionnairePublicDTO(
                q.getId(), pkg.getId(), pkg.getName(),
                q.getTargetRelationship(), q.getStatus(),
                q.getExpiresAt(), q.getSubmittedAt(),
                formTypes, sections
        );
    }

    // ── Submit questionnaire (auth required) ──────────────────────────────────

    @Transactional
    public void submitQuestionnaire(String token, SubmitQuestionnaireRequest req) {
        User caller = currentUser();

        FilingPackageQuestionnaire q = questionnaireRepo.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Questionnaire not found"));

        if (!"PENDING".equals(q.getStatus()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Questionnaire already submitted");
        if (LocalDateTime.now().isAfter(q.getExpiresAt()))
            throw new ResponseStatusException(HttpStatus.GONE, "This questionnaire link has expired");

        // Validate that the logged-in user is the correct target
        FilingPackage pkg = q.getFilingPackage();
        ImmigrationCase immCase = caseRepo.findById(pkg.getCaseId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found"));
        validateSubmitter(q.getTargetRelationship(), immCase, caller);

        // Upsert answers
        Map<String, String> answers = req.answers() != null ? req.answers() : Collections.emptyMap();
        List<String> questionKeys = parseStringList(q.getQuestionnaireSpecJson());

        for (String key : questionKeys) {
            String value = answers.get(key);
            if (value == null || value.isBlank()) continue;

            Optional<CanonicalQuestion> cqOpt = questionRegistry.findByKey(key);
            boolean sensitive = cqOpt.map(cq -> cq.isEncrypt()).orElse(false);
            String owner = cqOpt.map(CanonicalQuestion::getOwner).orElse(q.getTargetRelationship());

            FilingPackageAnswer ans = answerRepo
                    .findByFilingPackageIdAndQuestionKey(pkg.getId(), key)
                    .orElseGet(() -> {
                        FilingPackageAnswer a = new FilingPackageAnswer();
                        a.setFilingPackage(pkg);
                        a.setQuestionKey(key);
                        a.setOwner(owner);
                        return a;
                    });

            ans.setSource("questionnaire");
            ans.setAnsweredByUserId(caller.getId());
            if (sensitive) {
                ans.setValueJson(encryptionService.encrypt(value));
                ans.setValueHash(sha256(value));
            } else {
                ans.setValueJson(value);
                ans.setValueHash(null);
            }
            answerRepo.save(ans);
        }

        // Write back to canonical data sources
        writeBackAnswers(q.getTargetRelationship(), immCase, answers);

        // Mark questionnaire submitted
        q.setStatus("SUBMITTED");
        q.setSubmittedAt(LocalDateTime.now());
        q.setSubmittedByUserId(caller.getId());
        q.setSubmittedAnswersJson(toJson(answers));
        questionnaireRepo.save(q);

        // Audit: questionnaire submission
        auditService.appendCaseEvent(pkg.getCaseId(), "FilingPackageQuestionnaire", q.getId(),
                "questionnaire_submission", "QUESTIONNAIRE_SUBMITTED", "questionnaire",
                "{\"packageId\":" + pkg.getId() + ",\"targetRelationship\":\"" + q.getTargetRelationship() + "\"}",
                caller.getId());

        // Auto-transition package to ANSWERS_COLLECTED if all questionnaires submitted
        List<FilingPackageQuestionnaire> allQs = questionnaireRepo.findByFilingPackageId(pkg.getId());
        boolean allSubmitted = allQs.stream().allMatch(qs -> "SUBMITTED".equals(qs.getStatus()));
        if (allSubmitted && "QUESTIONNAIRES_SENT".equals(pkg.getStatus())) {
            pkg.setStatus("ANSWERS_COLLECTED");
            packageRepo.save(pkg);
        }

        // Notify assigned attorney
        if (immCase.getAssignedAttorneyMemberId() != null) {
            orgMemberRepo.findById(immCase.getAssignedAttorneyMemberId()).ifPresent(member ->
                    emailService.sendSimpleEmail(member.getEmail(),
                            "Questionnaire submitted — " + pkg.getName(),
                            q.getTargetRelationship() + " has submitted their questionnaire for package \""
                            + pkg.getName() + "\".")
            );
        }
    }

    // ── Attorney override ─────────────────────────────────────────────────────

    @Transactional
    public FilingPackageAnswerDTO overrideAnswer(Long caseId, Long packageId, String answerKey,
                                                 AnswerOverrideRequest req) {
        User caller = currentUser();
        permissionService.requireAccess(caller, caseId, GrantScope.APPROVE_FORMS);

        FilingPackage pkg = packageRepo.findByIdAndCaseId(packageId, caseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Package not found"));

        if (req.value() == null || req.value().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Value is required for override");

        FilingPackageAnswer ans = answerRepo
                .findByFilingPackageIdAndQuestionKey(pkg.getId(), answerKey)
                .orElseGet(() -> {
                    FilingPackageAnswer a = new FilingPackageAnswer();
                    a.setFilingPackage(pkg);
                    a.setQuestionKey(answerKey);
                    // owner from registry; default ATTORNEY if unknown
                    questionRegistry.findByKey(answerKey)
                            .ifPresentOrElse(cq -> a.setOwner(cq.getOwner()), () -> a.setOwner("ATTORNEY"));
                    return a;
                });

        boolean sensitive = questionRegistry.findByKey(answerKey)
                .map(cq -> cq.isEncrypt()).orElse(false);

        ans.setSource("attorney_override");
        ans.setAnsweredByUserId(caller.getId());
        ans.setAttorneyOverrideReason(req.overrideReason());
        if (sensitive) {
            ans.setValueJson(encryptionService.encrypt(req.value()));
            ans.setValueHash(sha256(req.value()));
        } else {
            ans.setValueJson(req.value());
            ans.setValueHash(null);
        }
        ans = answerRepo.save(ans);

        return toAnswerDTO(ans);
    }

    // ── Review summary ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ReviewSummaryDTO getReviewSummary(Long caseId, Long packageId) {
        User caller = currentUser();
        permissionService.requireAccess(caller, caseId, GrantScope.READ_CASE);

        FilingPackage pkg = packageRepo.findByIdAndCaseId(packageId, caseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Package not found"));

        List<String> formTypes = parseFormTypes(pkg.getSelectedFormTypesJson());
        List<CanonicalQuestion> questions = questionRegistry.getQuestionsForForms(formTypes);
        List<FilingPackageAnswer> dbAnswers = answerRepo.findByFilingPackageId(pkg.getId());
        Map<String, FilingPackageAnswer> answerByKey = dbAnswers.stream()
                .collect(Collectors.toMap(FilingPackageAnswer::getQuestionKey, a -> a, (a, b) -> a));

        LocalDateTime staleThreshold = LocalDateTime.now().minusDays(90);

        Map<String, List<ReviewSummaryDTO.AnswerSummary>> byOwner = new LinkedHashMap<>();
        List<String> missingRequired = new ArrayList<>();
        int totalRequired = 0, totalAnswered = 0;

        for (CanonicalQuestion cq : questions) {
            String owner = cq.getOwner() != null ? cq.getOwner() : "UNKNOWN";
            boolean required = cq.isRequired();
            boolean sensitive = cq.isEncrypt();
            FilingPackageAnswer ans = answerByKey.get(cq.getKey());
            boolean hasValue = ans != null && ans.getValueJson() != null && !ans.getValueJson().isBlank();
            String source = ans != null && ans.getSource() != null ? ans.getSource() : "none";

            // stale = prefill from profile/org and not verified within 90 days
            boolean stale = hasValue
                    && ("profile".equals(source) || "org".equals(source))
                    && (ans.getVerifiedAt() == null || ans.getVerifiedAt().isBefore(staleThreshold));

            if (required) {
                totalRequired++;
                if (hasValue) totalAnswered++;
                else missingRequired.add(cq.getKey());
            }

            byOwner.computeIfAbsent(owner, k -> new ArrayList<>())
                    .add(new ReviewSummaryDTO.AnswerSummary(
                            cq.getKey(), cq.getLabel(), cq.getType(),
                            required, hasValue, source, sensitive, stale
                    ));
        }

        int overallPct = totalRequired == 0 ? 100
                : (int) Math.round(100.0 * totalAnswered / totalRequired);

        List<ReviewSummaryDTO.OwnerAnswerGroup> ownerGroups = byOwner.entrySet().stream().map(e -> {
            List<ReviewSummaryDTO.AnswerSummary> ownerAnswers = e.getValue();
            long ownerReq = ownerAnswers.stream().filter(ReviewSummaryDTO.AnswerSummary::required).count();
            long ownerAns = ownerAnswers.stream().filter(a -> a.required() && a.hasValue()).count();
            int pct = ownerReq == 0 ? 100 : (int) Math.round(100.0 * ownerAns / ownerReq);
            return new ReviewSummaryDTO.OwnerAnswerGroup(e.getKey(), pct, ownerAnswers);
        }).toList();

        return new ReviewSummaryDTO(pkg.getId(), pkg.getName(), pkg.getStatus(),
                totalRequired, totalAnswered, overallPct, missingRequired, ownerGroups);
    }

    // ── Approve answers ───────────────────────────────────────────────────────

    @Transactional
    public FilingPackageDTO approveAnswers(Long caseId, Long packageId) {
        User caller = currentUser();
        permissionService.requireAccess(caller, caseId, GrantScope.APPROVE_FORMS);

        FilingPackage pkg = packageRepo.findByIdAndCaseId(packageId, caseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Package not found"));

        pkg.setStatus("APPROVED");
        pkg.setApprovedByUserId(caller.getId());
        pkg.setApprovedAt(LocalDateTime.now());
        pkg = packageRepo.save(pkg);

        List<FilingPackageQuestionnaire> qs = questionnaireRepo.findByFilingPackageId(pkg.getId());
        List<String> formTypes = parseFormTypes(pkg.getSelectedFormTypesJson());
        List<CanonicalQuestion> questions = questionRegistry.getQuestionsForForms(formTypes);
        List<FilingPackageAnswer> answers = answerRepo.findByFilingPackageId(pkg.getId());
        return toDTO(pkg, qs, questions, answersToResolvedMap(answers));
    }

    // ── Write-back helpers ────────────────────────────────────────────────────

    private void writeBackAnswers(String targetRelationship, ImmigrationCase immCase,
                                  Map<String, String> answers) {
        switch (targetRelationship) {
            case "BENEFICIARY" -> {
                canonicalProfileRepo.findByBeneficiary(immCase.getBeneficiary()).ifPresent(profile -> {
                    applyToProfile(profile, answers);
                    canonicalProfileRepo.save(profile);
                });
            }
            case "EMPLOYER" -> {
                if (immCase.getEmployerImmOrgId() != null) {
                    orgRepo.findById(immCase.getEmployerImmOrgId()).ifPresent(org -> {
                        applyToOrg(org, answers);
                        orgRepo.save(org);
                    });
                }
            }
            // ATTORNEY answers don't write back to a shared entity
        }
    }

    private void applyToProfile(CanonicalProfile p, Map<String, String> a) {
        applyStr(a, "beneficiary.firstName",     p::setLegalFirstName);
        applyStr(a, "beneficiary.lastName",      p::setLegalLastName);
        applyStr(a, "beneficiary.middleName",    p::setMiddleName);
        applyStr(a, "beneficiary.countryOfBirth", p::setCountryOfBirth);
        applyStr(a, "beneficiary.citizenshipCountry", p::setCitizenshipCountry);
        applyStr(a, "beneficiary.gender",        p::setGender);
        applyStr(a, "beneficiary.phone",         p::setPhone);
        applyStr(a, "beneficiary.uscisAccountNumber", p::setUscisOnlineAccountNumber);
        applyStr(a, "beneficiary.passportCountry", p::setPassportCountry);
        applyStr(a, "beneficiary.currentVisaType", p::setCurrentVisaType);
        applyStr(a, "beneficiary.eadCategory",   p::setEadCategory);
        applyStr(a, "beneficiary.eadCaseNumber", p::setEadCaseNumber);
        // Encrypted fields
        applyEncrypted(a, "beneficiary.passportNumber", p::setPassportNumberEnc);
        applyEncrypted(a, "beneficiary.alienNumber",    p::setAlienNumberEnc);
        applyEncrypted(a, "beneficiary.ssn",            p::setSsnEnc);
        applyEncrypted(a, "beneficiary.eadCardNumber",  p::setEadCardNumberEnc);
        if (a.containsKey("beneficiary.i94Number") && !a.get("beneficiary.i94Number").isBlank()) {
            String plain = a.get("beneficiary.i94Number");
            p.setI94NumberEnc(encryptionService.encrypt(plain));
            p.setI94Number(plain); // keep legacy column in sync
        }
    }

    private void applyToOrg(ImmOrg org, Map<String, String> a) {
        applyStr(a, "employer.legalName",    org::setName);
        applyStr(a, "employer.ein",          org::setEinNumber);
        applyStr(a, "employer.addressLine1", org::setAddress);
        applyStr(a, "employer.city",         org::setCity);
        applyStr(a, "employer.state",        org::setStateCode);
        applyStr(a, "employer.zipCode",      org::setZipCode);
        applyStr(a, "employer.website",      org::setWebsite);
    }

    @FunctionalInterface private interface StringSetter { void set(String v); }

    private void applyStr(Map<String, String> answers, String key, StringSetter setter) {
        String v = answers.get(key);
        if (v != null && !v.isBlank()) setter.set(v);
    }

    private void applyEncrypted(Map<String, String> answers, String key, StringSetter setter) {
        String v = answers.get(key);
        if (v != null && !v.isBlank()) setter.set(encryptionService.encrypt(v));
    }

    // ── Validation ────────────────────────────────────────────────────────────

    private void validateSubmitter(String targetRelationship, ImmigrationCase immCase, User caller) {
        String callerEmail = caller.getEmail();
        switch (targetRelationship) {
            case "BENEFICIARY" -> {
                String expected = immCase.getBeneficiary().getUser().getEmail();
                if (!expected.equalsIgnoreCase(callerEmail))
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                            "This questionnaire is not for your account");
            }
            case "EMPLOYER" -> {
                if (immCase.getEmployerImmOrgId() == null)
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No employer org on this case");
                boolean isMember = orgMemberRepo.findByImmOrgId(immCase.getEmployerImmOrgId())
                        .stream().anyMatch(m -> callerEmail.equalsIgnoreCase(m.getEmail())
                                && "ACTIVE".equals(m.getStatus() != null ? m.getStatus().name() : ""));
                if (!isMember) {
                    // fallback: check contactEmail
                    boolean isContact = orgRepo.findById(immCase.getEmployerImmOrgId())
                            .map(o -> callerEmail.equalsIgnoreCase(o.getContactEmail()))
                            .orElse(false);
                    if (!isContact)
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                                "This questionnaire is not for your account");
                }
            }
            case "ATTORNEY" -> {
                if (immCase.getAssignedAttorneyMemberId() == null)
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No attorney assigned to this case");
                String expected = orgMemberRepo.findById(immCase.getAssignedAttorneyMemberId())
                        .map(ImmOrgMember::getEmail)
                        .orElse(null);
                if (expected == null || !expected.equalsIgnoreCase(callerEmail))
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                            "This questionnaire is not for your account");
            }
        }
    }

    // ── Resolution context ────────────────────────────────────────────────────

    private DataResolver.ResolutionContext buildContext(ImmigrationCase immCase) {
        CanonicalProfile profile = canonicalProfileRepo
                .findByBeneficiary(immCase.getBeneficiary()).orElse(null);

        ImmOrg employerOrg = immCase.getEmployerImmOrgId() != null
                ? orgRepo.findById(immCase.getEmployerImmOrgId()).orElse(null) : null;

        ImmOrg lawFirmOrg = immCase.getLawFirmImmOrgId() != null
                ? orgRepo.findById(immCase.getLawFirmImmOrgId()).orElse(null) : null;

        AttorneyProfile attorneyProfile = null;
        if (immCase.getAssignedAttorneyMemberId() != null) {
            attorneyProfile = attorneyProfileRepo
                    .findByImmOrgMemberId(immCase.getAssignedAttorneyMemberId()).orElse(null);
        }

        return DataResolver.ResolutionContext.of(profile, employerOrg, lawFirmOrg, immCase, attorneyProfile);
    }

    // ── DTO builders ──────────────────────────────────────────────────────────

    private FilingPackageDTO toDTO(FilingPackage pkg,
                                   List<FilingPackageQuestionnaire> questionnaires,
                                   List<CanonicalQuestion> questions,
                                   Map<String, ResolvedValue> resolved) {
        List<String> formTypes = parseFormTypes(pkg.getSelectedFormTypesJson());

        // Completeness per owner
        Map<String, List<CanonicalQuestion>> byOwner = questionRegistry.getQuestionsByOwner(questions);
        Map<String, Integer> completeness = new LinkedHashMap<>();
        for (Map.Entry<String, List<CanonicalQuestion>> e : byOwner.entrySet()) {
            long req = e.getValue().stream().filter(q -> q.isRequired()).count();
            long ans = e.getValue().stream()
                    .filter(q -> q.isRequired())
                    .filter(q -> resolved.getOrDefault(q.getKey(), ResolvedValue.none()).hasValue())
                    .count();
            completeness.put(e.getKey(), req == 0 ? 100 : (int) Math.round(100.0 * ans / req));
        }

        List<FilingPackageQuestionnaireDTO> qDTOs = questionnaires.stream()
                .map(q -> toQuestionnaireDTO(q, questions, resolved))
                .toList();

        return new FilingPackageDTO(
                pkg.getId(), pkg.getCaseId(), pkg.getName(), formTypes,
                pkg.getStatus(), pkg.getApprovedAt(), pkg.getCreatedAt(), pkg.getUpdatedAt(),
                qDTOs, completeness
        );
    }

    private FilingPackageQuestionnaireDTO toQuestionnaireDTO(FilingPackageQuestionnaire q,
                                                              List<CanonicalQuestion> allQuestions,
                                                              Map<String, ResolvedValue> resolved) {
        List<String> keys = parseStringList(q.getQuestionnaireSpecJson());
        int questionCount = keys.size();
        int answeredCount = (int) keys.stream()
                .filter(k -> resolved.getOrDefault(k, ResolvedValue.none()).hasValue())
                .count();
        return new FilingPackageQuestionnaireDTO(
                q.getId(), q.getFilingPackage().getId(), q.getTargetRelationship(),
                q.getToken(), q.getStatus(), q.getSubmittedAt(), q.getExpiresAt(),
                q.getCreatedAt(), questionCount, answeredCount
        );
    }

    private FilingPackageAnswerDTO toAnswerDTO(FilingPackageAnswer ans) {
        boolean sensitive = questionRegistry.findByKey(ans.getQuestionKey())
                .map(cq -> cq.isEncrypt()).orElse(false);
        boolean hasValue = ans.getValueJson() != null && !ans.getValueJson().isBlank();
        String display = hasValue ? (sensitive ? "••••••" : ans.getValueJson()) : null;
        return new FilingPackageAnswerDTO(
                ans.getId(), ans.getQuestionKey(), display,
                ans.getOwner(), ans.getSource(), hasValue, sensitive,
                ans.getVerifiedAt(), ans.getAttorneyOverrideReason(), ans.getUpdatedAt()
        );
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private Map<String, ResolvedValue> answersToResolvedMap(List<FilingPackageAnswer> answers) {
        Map<String, ResolvedValue> map = new LinkedHashMap<>();
        for (FilingPackageAnswer ans : answers) {
            if (ans.getValueJson() != null && !ans.getValueJson().isBlank()) {
                map.put(ans.getQuestionKey(),
                        new ResolvedValue(ans.getValueJson(), ans.getSource(), null));
            }
        }
        return map;
    }

    private List<String> parseFormTypes(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse selectedFormTypesJson: {}", e.getMessage());
            return List.of();
        }
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse string list JSON: {}", e.getMessage());
            return List.of();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.error("JSON serialization failed: {}", e.getMessage());
            return "[]";
        }
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            log.warn("SHA-256 failed: {}", e.getMessage());
            return null;
        }
    }
}
