package com.receipttracker.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.receipttracker.dto.ParsedReceiptData;
import com.receipttracker.dto.ParsedReceiptItem;
import com.receipttracker.model.ReceiptType;
import com.receipttracker.model.StoreType;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

@Service
public class ClaudeVisionService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeVisionService.class);

    private static final String ANTHROPIC_API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private static final String RECEIPT_PROMPT =
            "You are a financial document data extraction engine. Output ONLY valid JSON, no explanation, no markdown.\n\n" +
            "First determine if this document is a RECEIPT/INVOICE or a BANK_STATEMENT.\n\n" +
            "For RECEIPTS and INVOICES use this exact structure:\n" +
            "{\n" +
            "  \"receiptType\": \"PURCHASE|RETURN|INVOICE\",\n" +
            "  \"storeName\": \"exact store name as printed\",\n" +
            "  \"storeType\": \"COSTCO|GAS_STATION|GROCERY|RESTAURANT|PHARMACY|ONLINE|OTHER\",\n" +
            "  \"purchaseDate\": \"YYYY-MM-DD or null\",\n" +
            "  \"purchaseTime\": \"HH:mm:ss or null\",\n" +
            "  \"cardType\": \"VISA|MASTERCARD|AMEX|DISCOVER|DEBIT or null\",\n" +
            "  \"cardBank\": \"CHASE|DISCOVER|AMEX|CAPITAL_ONE|CITI|BANK_OF_AMERICA|WELLS_FARGO or null\",\n" +
            "  \"lastFourDigits\": \"4-digit string or null\",\n" +
            "  \"subtotal\": number_or_null,\n" +
            "  \"tax\": number_or_null,\n" +
            "  \"tip\": number_or_null,\n" +
            "  \"total\": number_or_null,\n" +
            "  \"items\": [\n" +
            "    {\"name\": \"string\", \"description\": null, \"quantity\": 1, \"unitPrice\": number_or_null,\n" +
            "     \"totalPrice\": number, \"category\": \"GROCERIES|ELECTRONICS|CLOTHING|HOUSEHOLD|AUTOMOTIVE|DINING|GAS|PHARMACY|OTHER or null\"}\n" +
            "  ]\n" +
            "}\n\n" +
            "For BANK STATEMENTS use this exact structure:\n" +
            "{\n" +
            "  \"receiptType\": \"BANK_STATEMENT\",\n" +
            "  \"storeName\": \"bank institution name (e.g. Chase Bank, Bank of America)\",\n" +
            "  \"storeType\": \"BANK\",\n" +
            "  \"purchaseDate\": \"statement end date YYYY-MM-DD or null\",\n" +
            "  \"purchaseTime\": null,\n" +
            "  \"cardType\": null,\n" +
            "  \"cardBank\": \"CHASE|DISCOVER|AMEX|CAPITAL_ONE|CITI|BANK_OF_AMERICA|WELLS_FARGO or null\",\n" +
            "  \"lastFourDigits\": \"account last 4 digits or null\",\n" +
            "  \"subtotal\": null,\n" +
            "  \"tax\": null,\n" +
            "  \"tip\": null,\n" +
            "  \"total\": total_spending_as_positive_number_or_null,\n" +
            "  \"items\": [\n" +
            "    {\"name\": \"merchant or transaction description\", \"description\": \"YYYY-MM-DD transaction date\",\n" +
            "     \"quantity\": 1, \"unitPrice\": amount_as_positive_number, \"totalPrice\": amount_as_positive_number,\n" +
            "     \"category\": \"GROCERIES|DINING|GAS|TRAVEL|UTILITIES|HEALTHCARE|SHOPPING|ENTERTAINMENT|OTHER\"}\n" +
            "  ]\n" +
            "}\n\n" +
            "Rules:\n" +
            "1. PURCHASE=normal sale, RETURN=refund/return receipt, INVOICE=digital invoice (Amazon, Wayfair, etc.)\n" +
            "2. Use ONLINE for any e-commerce site. COSTCO only for Costco. GAS_STATION for fuel.\n" +
            "3. Monetary values are plain numbers without $ symbol (e.g. 12.99 not $12.99).\n" +
            "4. For bank statements: include debit/spending transactions only. Use positive amounts. Skip credits and deposits.\n" +
            "5. Multiple pages = one document — do not duplicate items across pages.\n" +
            "6. Costco: exclude item codes (5-8 digit numbers) from item names. Include instant savings as items with negative totalPrice.\n" +
            "7. Use null for any field not visible in the image. Never guess or infer values.\n" +
            "8. Output ONLY the JSON object. Nothing before or after it.";

    @Value("${vision.ai.enabled:false}")
    private boolean enabled;

    @Value("${vision.ai.api-key:}")
    private String apiKey;

    @Value("${vision.ai.model:claude-sonnet-4-6}")
    private String model;

    @Value("${vision.ai.max-pdf-pages:5}")
    private int maxPdfPages;

    @Value("${vision.ai.pdf-dpi:150}")
    private float pdfDpi;

    @Value("${vision.ai.timeout-seconds:60}")
    private int timeoutSeconds;

    @Autowired
    private ObjectMapper objectMapper;

    private volatile HttpClient httpClient;

    public Optional<ParsedReceiptData> analyze(File uploadedFile) {
        if (!enabled) {
            log.debug("Vision AI disabled — skipping");
            return Optional.empty();
        }
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Vision AI enabled but ANTHROPIC_API_KEY is not set — falling back to regex parser");
            return Optional.empty();
        }

        long start = System.currentTimeMillis();
        try {
            boolean isPdf = uploadedFile.getName().toLowerCase().endsWith(".pdf");
            List<byte[]> images = isPdf ? renderPdfToImages(uploadedFile) : List.of(readImageBytes(uploadedFile));

            if (images.isEmpty()) {
                log.warn("Vision AI: no images to analyze for {}", uploadedFile.getName());
                return Optional.empty();
            }

            String mediaType = isPdf ? "image/png" : detectImageMediaType(uploadedFile.getName());
            String requestBody = buildRequestBody(images, mediaType);
            String responseBody = callAnthropicApi(requestBody);
            ParsedReceiptData result = parseClaudeResponse(responseBody);

            log.info("Vision AI: analyzed {} in {}ms — store={}, type={}, items={}",
                    uploadedFile.getName(), System.currentTimeMillis() - start,
                    result.getStoreName(), result.getReceiptType(),
                    result.getItems() != null ? result.getItems().size() : 0);
            return Optional.of(result);

        } catch (Exception e) {
            log.warn("Vision AI: analysis failed for {} after {}ms — {} — falling back to regex parser",
                    uploadedFile.getName(), System.currentTimeMillis() - start, e.getMessage());
            return Optional.empty();
        }
    }

    public boolean isReadyForVision() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    public String detectImageMediaType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/png";
    }

    public List<byte[]> renderPdfToImages(File pdfFile) throws IOException {
        List<byte[]> images = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdfFile)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int pageCount = Math.min(doc.getNumberOfPages(), maxPdfPages);
            for (int i = 0; i < pageCount; i++) {
                BufferedImage img = renderer.renderImageWithDPI(i, pdfDpi, ImageType.RGB);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ImageIO.write(img, "PNG", bos);
                images.add(bos.toByteArray());
                log.debug("Vision AI: rendered PDF page {} ({} bytes)", i + 1, bos.size());
            }
        }
        return images;
    }

    public byte[] readImageBytes(File imageFile) throws IOException {
        return Files.readAllBytes(imageFile.toPath());
    }

    private String buildRequestBody(List<byte[]> images, String mediaType) throws Exception {
        return buildRequestBody(images, mediaType, RECEIPT_PROMPT);
    }

    public String buildRequestBody(List<byte[]> images, String mediaType, String prompt) throws Exception {
        List<Object> contentBlocks = new ArrayList<>();

        for (byte[] imageBytes : images) {
            String encoded = Base64.getEncoder().encodeToString(imageBytes);
            contentBlocks.add(objectMapper.createObjectNode()
                    .put("type", "image")
                    .set("source", objectMapper.createObjectNode()
                            .put("type", "base64")
                            .put("media_type", mediaType)
                            .put("data", encoded)));
        }

        contentBlocks.add(objectMapper.createObjectNode()
                .put("type", "text")
                .put("text", prompt));

        return objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("model", model)
                .put("max_tokens", 4096)
                .set("messages", objectMapper.createArrayNode()
                        .add(objectMapper.createObjectNode()
                                .put("role", "user")
                                .set("content", objectMapper.valueToTree(contentBlocks)))));
    }

    public String callAnthropicApi(String requestBody) throws IOException, InterruptedException {
        if (httpClient == null) {
            synchronized (this) {
                if (httpClient == null) {
                    httpClient = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(10))
                            .build();
                }
            }
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ANTHROPIC_API_URL))
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("content-type", "application/json")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.warn("Vision AI: Anthropic API returned HTTP {} — body: {}",
                    response.statusCode(), response.body());
            throw new IOException("Anthropic API error: HTTP " + response.statusCode());
        }

        log.debug("Vision AI: API response received ({} chars)", response.body().length());
        return response.body();
    }

    private ParsedReceiptData parseClaudeResponse(String rawApiResponse) throws IOException {
        JsonNode root = objectMapper.readTree(rawApiResponse);
        String text = root.path("content").path(0).path("text").asText();

        // Strip markdown code fences if Claude wrapped the JSON
        text = text.strip();
        if (text.startsWith("```")) {
            text = text.replaceFirst("```(?:json)?\\s*", "").replaceAll("```\\s*$", "").strip();
        }

        ClaudeReceiptJson json = objectMapper.readValue(text, ClaudeReceiptJson.class);
        return mapToReceiptData(json);
    }

    private ParsedReceiptData mapToReceiptData(ClaudeReceiptJson json) {
        ParsedReceiptData data = new ParsedReceiptData();

        data.setStoreName(json.storeName);
        data.setSubtotal(json.subtotal);
        data.setTax(json.tax);
        data.setTip(json.tip);
        data.setTotal(json.total);
        data.setCardType(json.cardType);
        data.setCardBank(json.cardBank);
        data.setLastFourDigits(json.lastFourDigits);

        if (json.cardBank != null && json.cardType != null && json.lastFourDigits != null) {
            data.setPaymentCard(json.cardBank + "_" + json.cardType + "_" + json.lastFourDigits);
        }

        data.setStoreType(parseStoreType(json.storeType));
        data.setReceiptType(parseReceiptType(json.receiptType));
        data.setPurchaseDateTime(parsePurchaseDateTime(json.purchaseDate, json.purchaseTime));

        List<ParsedReceiptItem> items = new ArrayList<>();
        if (json.items != null) {
            for (ClaudeItemJson item : json.items) {
                ParsedReceiptItem pi = new ParsedReceiptItem();
                pi.setName(item.name);
                // For bank statements, prefer the explicit description field; fall back to transactionDate
                String desc = item.description != null ? item.description
                            : item.transactionDate;
                pi.setDescription(desc);
                pi.setQuantity(item.quantity != null ? item.quantity : 1);
                pi.setUnitPrice(item.unitPrice);
                pi.setTotalPrice(item.totalPrice);
                pi.setCategory(item.category);
                items.add(pi);
            }
        }
        data.setItems(items);

        return data;
    }

    private StoreType parseStoreType(String value) {
        if (value == null) return StoreType.OTHER;
        try {
            return StoreType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return StoreType.OTHER;
        }
    }

    private ReceiptType parseReceiptType(String value) {
        if (value == null) return ReceiptType.PURCHASE;
        try {
            return ReceiptType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ReceiptType.PURCHASE;
        }
    }

    private LocalDateTime parsePurchaseDateTime(String date, String time) {
        if (date == null) return null;
        try {
            LocalDate localDate = LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE);
            if (time != null) {
                try {
                    LocalTime localTime = LocalTime.parse(time, DateTimeFormatter.ISO_LOCAL_TIME);
                    return LocalDateTime.of(localDate, localTime);
                } catch (DateTimeParseException e) {
                    log.debug("Vision AI: could not parse time '{}', using midnight", time);
                }
            }
            return localDate.atStartOfDay();
        } catch (DateTimeParseException e) {
            log.warn("Vision AI: could not parse date '{}': {}", date, e.getMessage());
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ClaudeReceiptJson {
        public String receiptType;
        public String storeName;
        public String storeType;
        public String purchaseDate;
        public String purchaseTime;
        public String cardType;
        public String cardBank;
        public String lastFourDigits;
        public BigDecimal subtotal;
        public BigDecimal tax;
        public BigDecimal tip;
        public BigDecimal total;
        public List<ClaudeItemJson> items;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ClaudeItemJson {
        public String name;
        public String description;
        public Integer quantity;
        public BigDecimal unitPrice;
        public BigDecimal totalPrice;
        public String category;
        public String transactionDate;
    }
}
