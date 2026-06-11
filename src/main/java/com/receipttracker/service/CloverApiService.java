package com.receipttracker.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.*;

@Service
public class CloverApiService {

    private static final Logger log = LoggerFactory.getLogger(CloverApiService.class);

    private final RestTemplate rest = new RestTemplate();

    // ── Credentials record ─────────────────────────────────────────────────────

    public record CloverCreds(String accessToken, String environment, String merchantId) {

        public String baseUrl() {
            return "PRODUCTION".equalsIgnoreCase(environment)
                    ? "https://api.clover.com"
                    : "https://apisandbox.dev.clover.com";
        }

        public boolean isConfigured() {
            return accessToken != null && !accessToken.isBlank()
                    && merchantId != null && !merchantId.isBlank();
        }
    }

    // ── Locations ──────────────────────────────────────────────────────────────

    /**
     * Clover is merchant-scoped — one merchant = one address.
     * Returns a single-element list so the shop treats it like Square locations.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getLocationsForCreds(CloverCreds creds) {
        String url = creds.baseUrl() + "/v3/merchants/" + creds.merchantId();
        try {
            ResponseEntity<Map> resp = rest.exchange(url, HttpMethod.GET, entity(creds), Map.class);
            Map<String, Object> merchant = resp.getBody();
            if (merchant == null) return List.of();

            Map<String, Object> location = new LinkedHashMap<>();
            location.put("id", creds.merchantId());
            location.put("name", merchant.getOrDefault("name", "Clover Store").toString());

            // Flatten address if present
            Object addrObj = merchant.get("address");
            if (addrObj instanceof Map<?, ?> addr) {
                String line = buildAddress(addr);
                if (!line.isBlank()) location.put("address", line);
            }
            location.put("phoneNumber", merchant.get("phone_number"));
            location.put("logoUrl",     merchant.get("logo_url"));

            return List.of(location);
        } catch (Exception e) {
            log.warn("Clover getLocations failed for merchant={}: {}", creds.merchantId(), e.getMessage());
            throw new RuntimeException("Clover API error: " + e.getMessage(), e);
        }
    }

    // ── Catalog ────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getCatalogItemsForCreds(CloverCreds creds) {
        String url = creds.baseUrl() + "/v3/merchants/" + creds.merchantId()
                + "/items?expand=categories";
        try {
            ResponseEntity<Map> resp = rest.exchange(url, HttpMethod.GET, entity(creds), Map.class);
            Map<String, Object> body = resp.getBody();
            if (body == null) return List.of();

            List<Map<String, Object>> elements = (List<Map<String, Object>>) body.get("elements");
            if (elements == null) return List.of();

            List<Map<String, Object>> items = new ArrayList<>();
            for (Map<String, Object> el : elements) {
                if (!Boolean.TRUE.equals(el.get("hidden"))) {
                    items.add(toItemDTO(el));
                }
            }
            return items;
        } catch (Exception e) {
            log.warn("Clover getCatalog failed for merchant={}: {}", creds.merchantId(), e.getMessage());
            throw new RuntimeException("Clover catalog error: " + e.getMessage(), e);
        }
    }

    // ── Test connection ────────────────────────────────────────────────────────

    public void testConnectionForCreds(CloverCreds creds) {
        getLocationsForCreds(creds); // throws on failure
    }

    // ── Create order only (pay at store — no charge) ──────────────────────────

    @SuppressWarnings("unchecked")
    public Map<String, Object> createOrderForCreds(CloverCreds creds, String currency) {
        String base = creds.baseUrl() + "/v3/merchants/" + creds.merchantId();
        Map<String, Object> orderBody = Map.of(
                "currency", currency != null ? currency : "USD",
                "state",    "open"
        );
        try {
            ResponseEntity<Map> resp = rest.exchange(
                    base + "/orders", HttpMethod.POST, entityWithBody(creds, orderBody), Map.class);
            Map<String, Object> order = Objects.requireNonNull(resp.getBody(), "Clover order response null");
            return Map.of(
                    "orderId",  order.get("id").toString(),
                    "currency", currency != null ? currency : "USD"
            );
        } catch (Exception e) {
            log.warn("Clover createOrder failed for merchant={}: {}", creds.merchantId(), e.getMessage());
            throw new RuntimeException("Clover order error: " + e.getMessage(), e);
        }
    }

    // ── Create order + charge ──────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public Map<String, Object> createOrderAndChargeForCreds(CloverCreds creds,
                                                             String sourceId,
                                                             long amountCents,
                                                             String currency) {
        String base = creds.baseUrl() + "/v3/merchants/" + creds.merchantId();

        // 1. Create order
        Map<String, Object> orderBody = Map.of(
                "currency", currency != null ? currency : "USD",
                "state",    "open"
        );
        ResponseEntity<Map> orderResp = rest.exchange(
                base + "/orders", HttpMethod.POST, entityWithBody(creds, orderBody), Map.class);
        Map<String, Object> order = Objects.requireNonNull(orderResp.getBody(), "Clover order response null");
        String orderId = order.get("id").toString();

        // 2. Charge via atomic payment
        Map<String, Object> payBody = Map.of(
                "amount",    amountCents,
                "currency",  currency != null ? currency : "USD",
                "source",    sourceId,
                "order",     Map.of("id", orderId)
        );
        ResponseEntity<Map> payResp = rest.exchange(
                base + "/payments", HttpMethod.POST, entityWithBody(creds, payBody), Map.class);
        Map<String, Object> payment = Objects.requireNonNull(payResp.getBody(), "Clover payment response null");
        String paymentId = payment.get("id").toString();

        return Map.of(
                "orderId",    orderId,
                "paymentId",  paymentId,
                "totalCents", amountCents,
                "currency",   currency != null ? currency : "USD"
        );
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private HttpEntity<Void> entity(CloverCreds creds) {
        HttpHeaders h = headers(creds);
        return new HttpEntity<>(h);
    }

    private HttpEntity<Map<String, Object>> entityWithBody(CloverCreds creds, Map<String, Object> body) {
        return new HttpEntity<>(body, headers(creds));
    }

    private HttpHeaders headers(CloverCreds creds) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(creds.accessToken());
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toItemDTO(Map<String, Object> el) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id",   el.get("id"));
        dto.put("name", el.get("name"));

        // price in cents → BigDecimal dollars
        Object priceObj = el.get("price");
        long priceCents = priceObj instanceof Number n ? n.longValue() : 0L;
        dto.put("price",    BigDecimal.valueOf(priceCents).movePointLeft(2));
        dto.put("currency", "USD");
        dto.put("available", !Boolean.TRUE.equals(el.get("hidden")));

        // Category id + name from expand
        Object catObj = el.get("categories");
        if (catObj instanceof Map<?, ?> cats) {
            List<?> catElements = (List<?>) cats.get("elements");
            if (catElements != null && !catElements.isEmpty()) {
                Map<?, ?> first = (Map<?, ?>) catElements.get(0);
                Object catId = first.get("id");
                dto.put("categoryId",   catId != null ? catId.toString() : first.get("name"));
                dto.put("categoryName", first.get("name"));
            }
        }

        // Clover items have a single price (no variations like Square)
        dto.put("variations", List.of(Map.of(
                "id",        el.get("id"),
                "name",      "Default",
                "price",     BigDecimal.valueOf(priceCents).movePointLeft(2),
                "currency",  "USD",
                "available", !Boolean.TRUE.equals(el.get("hidden"))
        )));

        return dto;
    }

    private String buildAddress(Map<?, ?> addr) {
        StringBuilder sb = new StringBuilder();
        appendPart(sb, addr.get("address1"));
        appendPart(sb, addr.get("city"));
        appendPart(sb, addr.get("state"));
        appendPart(sb, addr.get("zip"));
        return sb.toString().trim();
    }

    private void appendPart(StringBuilder sb, Object val) {
        if (val != null && !val.toString().isBlank()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(val);
        }
    }
}
