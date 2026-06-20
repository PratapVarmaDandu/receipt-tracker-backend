package com.receipttracker.immigration.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.receipttracker.immigration.dto.ScanResult;
import com.receipttracker.immigration.dto.ScanResult.FieldExtraction;
import com.receipttracker.immigration.model.GrantScope;
import com.receipttracker.immigration.repository.ImmigrationCaseRepository;
import com.receipttracker.model.User;
import com.receipttracker.repository.UserRepository;
import com.receipttracker.service.ClaudeVisionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

@Service
public class ImmDocumentScanService {

    private static final Logger log = LoggerFactory.getLogger(ImmDocumentScanService.class);

    private static final double CONFIDENCE_THRESHOLD = 0.85;

    private static final String DETECTION_PROMPT =
            "Classify this immigration document. Output ONLY one of these exact strings:\n" +
            "PASSPORT | US_VISA_STAMP | I94_PRINTOUT | I797_NOTICE | I20_FORM | EAD_CARD | " +
            "I140_APPROVAL | DS2019 | UNKNOWN\n" +
            "Output ONLY the classification string, nothing else.";

    private static final String PASSPORT_PROMPT =
            "You are an immigration document data extraction engine. Output ONLY valid JSON, no explanation, no markdown.\n" +
            "Extract all readable fields from this passport biographical page.\n" +
            "{\n" +
            "  \"docType\": \"PASSPORT\",\n" +
            "  \"lastName\": \"string or null\",\n" +
            "  \"firstName\": \"string or null\",\n" +
            "  \"middleName\": \"string or null\",\n" +
            "  \"nationality\": \"ISO 3166-1 alpha-3 country code or full name\",\n" +
            "  \"dateOfBirth\": \"YYYY-MM-DD or null\",\n" +
            "  \"gender\": \"M or F or null\",\n" +
            "  \"passportNumber\": \"string or null\",\n" +
            "  \"issuingCountry\": \"country name or null\",\n" +
            "  \"issueDate\": \"YYYY-MM-DD or null\",\n" +
            "  \"expiryDate\": \"YYYY-MM-DD or null\",\n" +
            "  \"placeOfBirth\": \"string or null\",\n" +
            "  \"mrz1\": \"first MRZ line verbatim or null\",\n" +
            "  \"mrz2\": \"second MRZ line verbatim or null\",\n" +
            "  \"confidence\": { \"fieldKey\": 0.0 }\n" +
            "}\n" +
            "Rules: never guess. Use null for any field not clearly visible.";

    private static final String I94_PROMPT =
            "You are an immigration document data extraction engine. Output ONLY valid JSON, no explanation, no markdown.\n" +
            "Extract all readable fields from this I-94 arrival/departure record.\n" +
            "{\n" +
            "  \"docType\": \"I94_PRINTOUT\",\n" +
            "  \"i94Number\": \"11-digit string or null\",\n" +
            "  \"lastName\": \"string or null\",\n" +
            "  \"firstName\": \"string or null\",\n" +
            "  \"dateOfBirth\": \"YYYY-MM-DD or null\",\n" +
            "  \"countryOfCitizenship\": \"string or null\",\n" +
            "  \"portOfEntry\": \"city/airport name or null\",\n" +
            "  \"entryDate\": \"YYYY-MM-DD or null\",\n" +
            "  \"admittedUntil\": \"YYYY-MM-DD or D/S or null\",\n" +
            "  \"visaClass\": \"e.g. H-1B or F-1 or null\",\n" +
            "  \"travelDocumentNumber\": \"passport number or null\",\n" +
            "  \"confidence\": { \"fieldKey\": 0.0 }\n" +
            "}\n" +
            "Rules: never guess. Use null for any field not clearly visible.";

    private static final String I797_PROMPT =
            "You are an immigration document data extraction engine. Output ONLY valid JSON, no explanation, no markdown.\n" +
            "Extract all readable fields from this USCIS I-797 approval/receipt notice.\n" +
            "{\n" +
            "  \"docType\": \"I797_NOTICE\",\n" +
            "  \"receiptNumber\": \"e.g. EAC2490012345 or null\",\n" +
            "  \"noticeType\": \"Approval Notice or Receipt Notice or null\",\n" +
            "  \"beneficiaryName\": \"string or null\",\n" +
            "  \"petitionerName\": \"string or null\",\n" +
            "  \"classOfAdmission\": \"e.g. H-1B or null\",\n" +
            "  \"validFrom\": \"YYYY-MM-DD or null\",\n" +
            "  \"validThrough\": \"YYYY-MM-DD or null\",\n" +
            "  \"priorityDate\": \"YYYY-MM-DD or null\",\n" +
            "  \"confidence\": { \"fieldKey\": 0.0 }\n" +
            "}\n" +
            "Rules: never guess. Use null for any field not clearly visible.";

    private static final String VISA_STAMP_PROMPT =
            "You are an immigration document data extraction engine. Output ONLY valid JSON, no explanation, no markdown.\n" +
            "Extract all readable fields from this US visa stamp page.\n" +
            "{\n" +
            "  \"docType\": \"US_VISA_STAMP\",\n" +
            "  \"visaType\": \"e.g. H-1B or F-1 or null\",\n" +
            "  \"lastName\": \"string or null\",\n" +
            "  \"firstName\": \"string or null\",\n" +
            "  \"passportNumber\": \"string or null\",\n" +
            "  \"nationality\": \"string or null\",\n" +
            "  \"dateOfBirth\": \"YYYY-MM-DD or null\",\n" +
            "  \"gender\": \"M or F or null\",\n" +
            "  \"issueDate\": \"YYYY-MM-DD or null\",\n" +
            "  \"expiryDate\": \"YYYY-MM-DD or null\",\n" +
            "  \"issuingPost\": \"embassy/consulate city or null\",\n" +
            "  \"entries\": \"M (multiple) or 1 or 2 or null\",\n" +
            "  \"controlNumber\": \"foil control number or null\",\n" +
            "  \"confidence\": { \"fieldKey\": 0.0 }\n" +
            "}\n" +
            "Rules: never guess. Use null for any field not clearly visible.";

    private static final String I20_PROMPT =
            "You are an immigration document data extraction engine. Output ONLY valid JSON, no explanation, no markdown.\n" +
            "Extract all readable fields from this Form I-20 Certificate of Eligibility.\n" +
            "{\n" +
            "  \"docType\": \"I20_FORM\",\n" +
            "  \"lastName\": \"string or null\",\n" +
            "  \"firstName\": \"string or null\",\n" +
            "  \"dateOfBirth\": \"YYYY-MM-DD or null\",\n" +
            "  \"countryOfBirth\": \"string or null\",\n" +
            "  \"countryOfCitizenship\": \"string or null\",\n" +
            "  \"sevisId\": \"N followed by 10 digits or null\",\n" +
            "  \"schoolName\": \"string or null\",\n" +
            "  \"programStartDate\": \"YYYY-MM-DD or null\",\n" +
            "  \"programEndDate\": \"YYYY-MM-DD or null\",\n" +
            "  \"educationLevel\": \"e.g. Bachelor's or Master's or null\",\n" +
            "  \"fieldOfStudy\": \"string or null\",\n" +
            "  \"confidence\": { \"fieldKey\": 0.0 }\n" +
            "}\n" +
            "Rules: never guess. Use null for any field not clearly visible.";

    private static final String EAD_PROMPT =
            "You are an immigration document data extraction engine. Output ONLY valid JSON, no explanation, no markdown.\n" +
            "Extract all readable fields from this Employment Authorization Document (EAD / Form I-766).\n" +
            "{\n" +
            "  \"docType\": \"EAD_CARD\",\n" +
            "  \"lastName\": \"string or null\",\n" +
            "  \"firstName\": \"string or null\",\n" +
            "  \"dateOfBirth\": \"YYYY-MM-DD or null\",\n" +
            "  \"countryOfBirth\": \"string or null\",\n" +
            "  \"uscisNumber\": \"A-number or null\",\n" +
            "  \"cardExpiryDate\": \"YYYY-MM-DD or null\",\n" +
            "  \"categoryCode\": \"e.g. C09 or A12 or null\",\n" +
            "  \"cardNumber\": \"string on back of card or null\",\n" +
            "  \"confidence\": { \"fieldKey\": 0.0 }\n" +
            "}\n" +
            "Rules: never guess. Use null for any field not clearly visible.";

    private static final Map<String, String> PROMPTS_BY_DOC_TYPE = Map.of(
            "PASSPORT",       PASSPORT_PROMPT,
            "I94_PRINTOUT",   I94_PROMPT,
            "I797_NOTICE",    I797_PROMPT,
            "I140_APPROVAL",  I797_PROMPT,   // similar notice structure
            "US_VISA_STAMP",  VISA_STAMP_PROMPT,
            "I20_FORM",       I20_PROMPT,
            "EAD_CARD",       EAD_PROMPT
    );

    @Autowired private ClaudeVisionService visionService;
    @Autowired private PermissionService permissionService;
    @Autowired private UserRepository userRepo;
    @Autowired private ImmigrationCaseRepository caseRepo;
    @Autowired private ObjectMapper objectMapper;

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        OAuth2User principal = (OAuth2User) auth.getPrincipal();
        String googleId = principal.getAttribute("sub");
        return userRepo.findByGoogleId(googleId)
                .orElseThrow(() -> new RuntimeException("Authenticated user not found"));
    }

    // ── Profile scan (any authenticated beneficiary) ──────────────────────────

    public ScanResult scanForProfile(MultipartFile file) {
        log.info(">>> scanForProfile() file={} size={}", file.getOriginalFilename(), file.getSize());
        validateFile(file);
        if (!visionService.isReadyForVision()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Document scanning requires Vision AI to be enabled (ANTHROPIC_API_KEY not configured).");
        }
        return detectAndExtract(file);
    }

    // ── Case document scan (ATTORNEY + PARALEGAL, uses WRITE_CASE grant) ──────

    public ScanResult scanForCase(Long caseId, MultipartFile file) {
        log.info(">>> scanForCase() caseId={} file={}", caseId, file.getOriginalFilename());
        User caller = currentUser();
        permissionService.requireAccess(caller, caseId, GrantScope.WRITE_CASE);
        validateFile(file);
        if (!visionService.isReadyForVision()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Document scanning requires Vision AI to be enabled (ANTHROPIC_API_KEY not configured).");
        }
        ScanResult result = detectAndExtract(file);

        // Suggest receipt number update for I-797 notices
        String suggestion = null;
        if ("I797_NOTICE".equals(result.docTypeDetected()) || "I140_APPROVAL".equals(result.docTypeDetected())) {
            FieldExtraction receiptNumberField = result.extractedFields().get("receiptNumber");
            if (receiptNumberField != null && receiptNumberField.value() != null
                    && !receiptNumberField.value().isBlank()) {
                suggestion = receiptNumberField.value();
            }
        }

        if (suggestion != null) {
            return new ScanResult(result.docTypeDetected(), result.extractedFields(),
                    result.lowConfidenceFields(), suggestion);
        }
        return result;
    }

    // ── Core detection + extraction ───────────────────────────────────────────

    private ScanResult detectAndExtract(MultipartFile file) {
        File tempFile = null;
        try {
            // 1. Save to temp file
            tempFile = Files.createTempFile("imm-scan-", "-" + file.getOriginalFilename()).toFile();
            file.transferTo(tempFile);

            boolean isPdf = isPdf(file.getOriginalFilename());
            String mediaType = isPdf ? "image/png"
                    : visionService.detectImageMediaType(file.getOriginalFilename());

            // 2. Render to images
            List<byte[]> images = isPdf
                    ? visionService.renderPdfToImages(tempFile)
                    : List.of(visionService.readImageBytes(tempFile));

            if (images.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Could not render document to images.");
            }

            // 3. Detect document type
            String detectionBody = visionService.buildRequestBody(images, mediaType, DETECTION_PROMPT);
            String detectionResponse = visionService.callAnthropicApi(detectionBody);
            String docType = extractTextContent(detectionResponse).strip().toUpperCase();

            log.info("Document type detected: {}", docType);

            // 4. Extract fields with type-specific prompt
            String extractionPrompt = PROMPTS_BY_DOC_TYPE.getOrDefault(docType,
                    PROMPTS_BY_DOC_TYPE.get("PASSPORT")); // fallback to passport prompt
            String extractionBody = visionService.buildRequestBody(images, mediaType, extractionPrompt);
            String extractionResponse = visionService.callAnthropicApi(extractionBody);
            String extractionJson = stripMarkdownFences(extractTextContent(extractionResponse));

            // 5. Parse fields + confidence
            return parseExtractionResult(docType, extractionJson);

        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("!!! Document scan failed: {}", e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Document scan failed: " + e.getMessage());
        } finally {
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    private ScanResult parseExtractionResult(String docType, String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        JsonNode confidenceNode = root.path("confidence");

        Map<String, FieldExtraction> fields = new LinkedHashMap<>();
        List<String> lowConfidence = new ArrayList<>();

        Iterator<Map.Entry<String, JsonNode>> it = root.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            String key = entry.getKey();
            if ("docType".equals(key) || "confidence".equals(key)
                    || "mrz1".equals(key) || "mrz2".equals(key)) continue;

            JsonNode valueNode = entry.getValue();
            if (valueNode.isNull()) continue;
            String value = valueNode.asText();
            if (value.isBlank()) continue;

            double conf = confidenceNode.path(key).asDouble(1.0);
            boolean needsReview = conf < CONFIDENCE_THRESHOLD;

            fields.put(key, new FieldExtraction(value, conf, needsReview));
            if (needsReview) lowConfidence.add(key);
        }

        return new ScanResult(docType, fields, lowConfidence, null);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No file provided.");
        }
        long maxBytes = 10L * 1024 * 1024;
        if (file.getSize() > maxBytes) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File exceeds 10 MB limit.");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!name.endsWith(".pdf") && !name.endsWith(".jpg") && !name.endsWith(".jpeg")
                && !name.endsWith(".png")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unsupported file type. Upload PDF, JPG, or PNG.");
        }
    }

    private boolean isPdf(String filename) {
        return filename != null && filename.toLowerCase().endsWith(".pdf");
    }

    private String extractTextContent(String apiResponse) throws Exception {
        JsonNode root = objectMapper.readTree(apiResponse);
        return root.path("content").path(0).path("text").asText();
    }

    private String stripMarkdownFences(String text) {
        text = text.strip();
        if (text.startsWith("```")) {
            text = text.replaceFirst("```(?:json)?\\s*", "").replaceAll("```\\s*$", "").strip();
        }
        return text;
    }
}
