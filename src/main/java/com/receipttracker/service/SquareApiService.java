package com.receipttracker.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.receipttracker.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SquareApiService {

    private static final Logger log = LoggerFactory.getLogger(SquareApiService.class);

    @Value("${square.access-token:}")
    private String accessToken;

    @Value("${square.environment:sandbox}")
    private String environment;

    @Value("${square.application-id:}")
    private String applicationId;

    @Value("${square.location-id:}")
    private String locationId;

    @Value("${app.frontend.url:http://localhost:4200}")
    private String frontendUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    private String baseUrl() {
        return "sandbox".equalsIgnoreCase(environment)
                ? "https://connect.squareupsandbox.com/v2"
                : "https://connect.squareup.com/v2";
    }

    private HttpHeaders authHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", "Bearer " + accessToken);
        h.set("Square-Version", "2024-11-20");
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    // ── Catalog ────────────────────────────────────────────────────────────────

    /**
     * Fetches catalog items from Square, including resolved images and category names.
     * Uses the catalog/search endpoint with include_related_objects so images and
     * categories are returned in a single API call.
     */
    public List<SquareCatalogItemDTO> getCatalogItems(String cursor) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("object_types", List.of("ITEM"));
            body.put("include_related_objects", true);
            body.put("include_deleted_objects", false);
            if (cursor != null && !cursor.isBlank()) {
                body.put("cursor", cursor);
            }

            HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, authHeaders());
            ResponseEntity<CatalogSearchResponse> resp = restTemplate.exchange(
                    baseUrl() + "/catalog/search",
                    HttpMethod.POST,
                    req,
                    CatalogSearchResponse.class
            );

            CatalogSearchResponse data = resp.getBody();
            if (data == null || data.objects == null) return List.of();

            // Build lookup maps from related_objects
            Map<String, String> imageUrlById  = new HashMap<>();
            Map<String, String> categoryNameById = new HashMap<>();

            if (data.relatedObjects != null) {
                for (CatalogObject obj : data.relatedObjects) {
                    if ("IMAGE".equals(obj.type) && obj.imageData != null) {
                        imageUrlById.put(obj.id, obj.imageData.url);
                    } else if ("CATEGORY".equals(obj.type) && obj.categoryData != null) {
                        categoryNameById.put(obj.id, obj.categoryData.name);
                    }
                }
            }

            return data.objects.stream()
                    .filter(o -> "ITEM".equals(o.type) && o.itemData != null)
                    .map(o -> toItemDTO(o, imageUrlById, categoryNameById))
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.warn("Square getCatalogItems failed: {}", e.getMessage());
            return List.of();
        }
    }

    public List<Map<String, String>> getCategories() {
        try {
            HttpEntity<Void> req = new HttpEntity<>(authHeaders());
            ResponseEntity<CatalogListResponse> resp = restTemplate.exchange(
                    baseUrl() + "/catalog/list?types=CATEGORY",
                    HttpMethod.GET,
                    req,
                    CatalogListResponse.class
            );

            CatalogListResponse data = resp.getBody();
            if (data == null || data.objects == null) return List.of();

            return data.objects.stream()
                    .filter(o -> "CATEGORY".equals(o.type) && o.categoryData != null)
                    .map(o -> {
                        Map<String, String> m = new LinkedHashMap<>();
                        m.put("id",   o.id);
                        m.put("name", o.categoryData.name);
                        return m;
                    })
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.warn("Square getCategories failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** Returns all ACTIVE locations for the connected Square account. */
    public List<Map<String, Object>> getLocations() {
        try {
            HttpEntity<Void> req = new HttpEntity<>(authHeaders());
            ResponseEntity<AllLocationsResponse> resp = restTemplate.exchange(
                    baseUrl() + "/locations",
                    HttpMethod.GET,
                    req,
                    AllLocationsResponse.class
            );
            AllLocationsResponse data = resp.getBody();
            if (data == null || data.locations == null) return List.of();

            return data.locations.stream()
                    .filter(l -> "ACTIVE".equals(l.status))
                    .map(l -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id",          l.id);
                        m.put("name",        l.name != null ? l.name : "Store");
                        m.put("description", l.description);
                        m.put("phoneNumber", l.phoneNumber);
                        m.put("logoUrl",     l.logoUrl);
                        m.put("address",     formatAddress(l.address));
                        return m;
                    })
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Square getLocations failed: {}", e.getMessage());
            return List.of();
        }
    }

    public String getLocationName(String forLocationId) {
        try {
            String id = (forLocationId != null && !forLocationId.isBlank()) ? forLocationId : locationId;
            HttpEntity<Void> req = new HttpEntity<>(authHeaders());
            ResponseEntity<SingleLocationResponse> resp = restTemplate.exchange(
                    baseUrl() + "/locations/" + id,
                    HttpMethod.GET,
                    req,
                    SingleLocationResponse.class
            );
            SingleLocationResponse data = resp.getBody();
            if (data != null && data.location != null && data.location.name != null) {
                return data.location.name;
            }
        } catch (Exception e) {
            log.warn("Square getLocationName failed: {}", e.getMessage());
        }
        return "Square Store";
    }

    private String formatAddress(LocationAddress addr) {
        if (addr == null) return null;
        StringBuilder sb = new StringBuilder();
        if (addr.addressLine1 != null) sb.append(addr.addressLine1);
        if (addr.locality != null)     { if (sb.length() > 0) sb.append(", "); sb.append(addr.locality); }
        if (addr.adminDistrict != null){ if (sb.length() > 0) sb.append(", "); sb.append(addr.adminDistrict); }
        if (addr.postalCode != null)   { if (sb.length() > 0) sb.append(" ");  sb.append(addr.postalCode); }
        return sb.length() > 0 ? sb.toString() : null;
    }

    // ── Config (for frontend SDK initialisation) ──────────────────────────────

    public String getApplicationId() { return applicationId; }
    public String getLocationId()    { return locationId;    }
    public String getEnvironment()   { return environment;   }

    // ── Orders / Checkout ─────────────────────────────────────────────────────

    /**
     * Creates a Square order and immediately charges the card using a sourceId
     * tokenized by the Square Web Payments SDK in the browser.
     * Returns { orderId, totalCents }.
     */
    public Map<String, Object> createOrderAndCharge(CreateSquareOrderRequest request) {
        String resolvedLocationId = (request.getLocationId() != null && !request.getLocationId().isBlank())
                ? request.getLocationId() : locationId;

        // 1. Create the order
        Map<String, Object> orderBody = new LinkedHashMap<>();
        orderBody.put("idempotency_key", UUID.randomUUID().toString());
        Map<String, Object> orderObj = new LinkedHashMap<>();
        orderObj.put("location_id",  resolvedLocationId);
        orderObj.put("line_items",   buildLineItems(request));
        orderObj.put("fulfillments", List.of(buildFulfillment(request)));
        orderBody.put("order", orderObj);

        HttpEntity<Map<String, Object>> orderReq = new HttpEntity<>(orderBody, authHeaders());
        ResponseEntity<OrderCreateResponse> orderResp = restTemplate.exchange(
                baseUrl() + "/orders",
                HttpMethod.POST,
                orderReq,
                OrderCreateResponse.class
        );

        OrderCreateResponse orderData = orderResp.getBody();
        if (orderData == null || orderData.order == null) {
            throw new RuntimeException("Empty response from Square orders API");
        }

        String orderId    = orderData.order.id;
        long   totalCents = orderData.order.totalMoney != null ? orderData.order.totalMoney.amount : 0L;

        // 2. Charge the card
        Map<String, Object> payBody = new LinkedHashMap<>();
        payBody.put("source_id",       request.getSourceId());
        payBody.put("idempotency_key", UUID.randomUUID().toString());
        payBody.put("order_id",        orderId);
        payBody.put("location_id",     resolvedLocationId);
        payBody.put("amount_money",    Map.of("amount", totalCents, "currency", "USD"));

        HttpEntity<Map<String, Object>> payReq = new HttpEntity<>(payBody, authHeaders());
        ResponseEntity<SquarePaymentResponse> payResp = restTemplate.exchange(
                baseUrl() + "/payments",
                HttpMethod.POST,
                payReq,
                SquarePaymentResponse.class
        );

        SquarePaymentResponse payData = payResp.getBody();
        if (payData == null || payData.payment == null) {
            throw new RuntimeException("Empty response from Square payments API");
        }
        if (!"COMPLETED".equals(payData.payment.status)) {
            throw new RuntimeException("Payment not completed — status: " + payData.payment.status);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId",    orderId);
        result.put("paymentId",  payData.payment.id);
        result.put("totalCents", totalCents);
        return result;
    }

    // ── Mapping helpers ───────────────────────────────────────────────────────

    private SquareCatalogItemDTO toItemDTO(CatalogObject obj,
                                           Map<String, String> imageUrlById,
                                           Map<String, String> categoryNameById) {
        ItemData d = obj.itemData;

        String imageUrl = null;
        if (d.imageIds != null && !d.imageIds.isEmpty()) {
            imageUrl = imageUrlById.get(d.imageIds.get(0));
        }

        String catName = d.categoryId != null ? categoryNameById.get(d.categoryId) : null;

        List<SquareVariationDTO> variations = new ArrayList<>();
        if (d.variations != null) {
            for (CatalogObject v : d.variations) {
                if (v.itemVariationData == null) continue;
                BigDecimal price = BigDecimal.ZERO;
                if (v.itemVariationData.priceMoney != null) {
                    // Square amounts are in cents
                    price = BigDecimal.valueOf(v.itemVariationData.priceMoney.amount)
                            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                }
                variations.add(new SquareVariationDTO(
                        v.id,
                        v.itemVariationData.name,
                        price,
                        v.itemVariationData.priceMoney != null ? v.itemVariationData.priceMoney.currency : "USD",
                        true
                ));
            }
        }

        return new SquareCatalogItemDTO(
                obj.id,
                d.name,
                d.description,
                d.categoryId,
                catName,
                imageUrl,
                variations
        );
    }

    private List<Map<String, Object>> buildLineItems(CreateSquareOrderRequest request) {
        List<Map<String, Object>> lineItems = new ArrayList<>();
        for (CreateSquareOrderRequest.CartLineItem li : request.getItems()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("quantity",          String.valueOf(li.getQuantity()));
            item.put("catalog_object_id", li.getVariationId());
            item.put("item_type",         "ITEM");
            lineItems.add(item);
        }
        return lineItems;
    }

    private Map<String, Object> buildFulfillment(CreateSquareOrderRequest request) {
        Map<String, Object> fulfillment = new LinkedHashMap<>();
        String type = "PICKUP".equalsIgnoreCase(request.getFulfillmentType()) ? "PICKUP" : "SHIPMENT";
        fulfillment.put("type",  type);
        fulfillment.put("state", "PROPOSED");

        Map<String, Object> recipient = new LinkedHashMap<>();
        recipient.put("display_name",  request.getRecipientName());
        recipient.put("email_address", request.getRecipientEmail());
        if (request.getRecipientPhone() != null && !request.getRecipientPhone().isBlank()) {
            recipient.put("phone_number", request.getRecipientPhone());
        }

        if ("PICKUP".equals(type)) {
            Map<String, Object> pickupDetails = new LinkedHashMap<>();
            pickupDetails.put("recipient",     recipient);
            pickupDetails.put("schedule_type", "ASAP");
            if (request.getNote() != null && !request.getNote().isBlank()) {
                pickupDetails.put("note", request.getNote());
            }
            fulfillment.put("pickup_details", pickupDetails);
        } else {
            Map<String, Object> shipDetails = new LinkedHashMap<>();
            shipDetails.put("recipient", recipient);
            if (request.getDeliveryAddress() != null && !request.getDeliveryAddress().isBlank()) {
                Map<String, Object> addr = new LinkedHashMap<>();
                addr.put("address_line_1", request.getDeliveryAddress());
                shipDetails.put("address", addr);
            }
            fulfillment.put("shipment_details", shipDetails);
        }
        return fulfillment;
    }

    // ── Square API response POJOs ─────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class CatalogSearchResponse {
        @JsonProperty("objects")          List<CatalogObject> objects;
        @JsonProperty("related_objects")  List<CatalogObject> relatedObjects;
        @JsonProperty("cursor")           String cursor;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class CatalogListResponse {
        @JsonProperty("objects") List<CatalogObject> objects;
        @JsonProperty("cursor")  String cursor;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class CatalogObject {
        @JsonProperty("type")                  String type;
        @JsonProperty("id")                    String id;
        @JsonProperty("item_data")             ItemData itemData;
        @JsonProperty("item_variation_data")   ItemVariationData itemVariationData;
        @JsonProperty("image_data")            ImageData imageData;
        @JsonProperty("category_data")         CategoryData categoryData;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ItemData {
        @JsonProperty("name")        String name;
        @JsonProperty("description") String description;
        @JsonProperty("category_id") String categoryId;
        @JsonProperty("image_ids")   List<String> imageIds;
        @JsonProperty("variations")  List<CatalogObject> variations;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ItemVariationData {
        @JsonProperty("name")        String name;
        @JsonProperty("price_money") PriceMoney priceMoney;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class PriceMoney {
        @JsonProperty("amount")   long amount;
        @JsonProperty("currency") String currency;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ImageData {
        @JsonProperty("url") String url;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class CategoryData {
        @JsonProperty("name") String name;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class AllLocationsResponse {
        @JsonProperty("locations") List<LocationObj> locations;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class SingleLocationResponse {
        @JsonProperty("location") LocationObj location;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class LocationObj {
        @JsonProperty("id")             String id;
        @JsonProperty("name")           String name;
        @JsonProperty("status")         String status;
        @JsonProperty("description")    String description;
        @JsonProperty("phone_number")   String phoneNumber;
        @JsonProperty("logo_url")       String logoUrl;
        @JsonProperty("address")        LocationAddress address;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class LocationAddress {
        @JsonProperty("address_line_1")                      String addressLine1;
        @JsonProperty("locality")                            String locality;
        @JsonProperty("administrative_district_level_1")     String adminDistrict;
        @JsonProperty("postal_code")                         String postalCode;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class OrderCreateResponse {
        @JsonProperty("order") OrderObj order;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class OrderObj {
        @JsonProperty("id")           String id;
        @JsonProperty("total_money")  PriceMoney totalMoney;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class SquarePaymentResponse {
        @JsonProperty("payment") PaymentObj payment;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class PaymentObj {
        @JsonProperty("id")           String id;
        @JsonProperty("status")       String status;
        @JsonProperty("amount_money") PriceMoney amountMoney;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class PaymentLinkResponse {
        @JsonProperty("payment_link") PaymentLink paymentLink;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class PaymentLink {
        @JsonProperty("id")       String id;
        @JsonProperty("url")      String url;
        @JsonProperty("order_id") String orderId;
    }
}
