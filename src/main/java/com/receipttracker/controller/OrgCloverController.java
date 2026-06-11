package com.receipttracker.controller;

import com.receipttracker.dto.*;
import com.receipttracker.model.*;
import com.receipttracker.repository.*;
import com.receipttracker.service.CloverApiService;
import com.receipttracker.service.OrganizationService;
import com.receipttracker.service.ReceiptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Per-org Clover credential management.
 * Role gates are enforced in OrganizationService (VIEWER read, ADMIN write, OWNER clear).
 */
@RestController
@RequestMapping("/api/organizations/{slug}/clover")
public class OrgCloverController {

    private static final Logger log = LoggerFactory.getLogger(OrgCloverController.class);

    @Autowired private OrganizationService orgService;
    @Autowired private CloverApiService cloverApiService;
    @Autowired private ReceiptService receiptService;
    @Autowired private OrgOrderRepository orgOrderRepo;
    @Autowired private OrganizationRepository orgRepo;
    @Autowired private UserRepository userRepo;

    @GetMapping
    public ResponseEntity<?> getConfig(@PathVariable String slug) {
        try {
            return ResponseEntity.ok(orgService.getCloverConfig(slug));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping
    public ResponseEntity<?> saveConfig(@PathVariable String slug,
                                        @RequestBody OrgCloverConfigRequest req) {
        try {
            return ResponseEntity.ok(orgService.saveCloverConfig(slug, req));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping
    public ResponseEntity<?> clearConfig(@PathVariable String slug) {
        try {
            orgService.clearCloverConfig(slug);
            return ResponseEntity.ok(Map.of("message", "Clover configuration cleared"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Validates saved credentials by fetching the merchant info from Clover. */
    @PostMapping("/test")
    public ResponseEntity<?> testConnection(@PathVariable String slug) {
        try {
            CloverApiService.CloverCreds creds = orgService.resolveCloverCreds(slug);
            List<Map<String, Object>> locations = cloverApiService.getLocationsForCreds(creds);
            String storeName = locations.isEmpty() ? "unknown" : locations.get(0).get("name").toString();
            return ResponseEntity.ok(Map.of(
                    "success",       true,
                    "locationCount", locations.size(),
                    "message",       "Connected — merchant \"" + storeName + "\""
            ));
        } catch (Exception e) {
            log.warn("Clover test connection failed for org={}: {}", slug, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error",   e.getMessage()
            ));
        }
    }

    /** Returns catalog items using the org's Clover credentials. */
    @GetMapping("/catalog")
    public ResponseEntity<?> getCatalog(@PathVariable String slug) {
        try {
            CloverApiService.CloverCreds creds = orgService.resolveCloverCreds(slug);
            return ResponseEntity.ok(cloverApiService.getCatalogItemsForCreds(creds));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Returns the merchant location using the org's Clover credentials. */
    @GetMapping("/locations")
    public ResponseEntity<?> getLocations(@PathVariable String slug) {
        try {
            CloverApiService.CloverCreds creds = orgService.resolveCloverCreds(slug);
            return ResponseEntity.ok(cloverApiService.getLocationsForCreds(creds));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Places a Clover order for in-store pickup/payment — no card charge.
     * Registers the order with Clover (so it appears in the merchant dashboard),
     * saves a Receipt and OrgOrder with status PENDING_PAYMENT.
     */
    @PostMapping("/orders")
    public ResponseEntity<?> placeOrder(@PathVariable String slug,
                                        @RequestBody CreateSquareOrderRequest request,
                                        Authentication authentication) {
        log.info("POST /api/organizations/{}/clover/orders items={}", slug,
                request.getItems() == null ? 0 : request.getItems().size());
        try {
            CloverApiService.CloverCreds creds = orgService.resolveCloverCreds(slug);

            // 1. Compute total
            BigDecimal total = request.getItems().stream()
                    .map(li -> li.getUnitPrice().multiply(BigDecimal.valueOf(li.getQuantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.HALF_UP);

            // 2. Register order with Clover (no charge)
            Map<String, Object> result = cloverApiService.createOrderForCreds(creds, "USD");
            String cloverOrderId = (String) result.get("orderId");

            // 3. Resolve store name
            List<Map<String, Object>> locations = cloverApiService.getLocationsForCreds(creds);
            String storeName = locations.isEmpty() ? "Clover Store" : locations.get(0).get("name").toString();

            // 4. Save personal receipt
            ReceiptDTO receiptDTO = buildReceiptDTO(request, storeName, total);
            ReceiptDTO savedReceipt = receiptService.saveManual(receiptDTO);

            // 5. Save org order — PENDING_PAYMENT (pay at store)
            Organization org = orgRepo.findBySlug(slug).orElseThrow();
            User placedBy = resolveUser(authentication);
            OrgOrder order = new OrgOrder();
            order.setOrg(org);
            order.setPlacedBy(placedBy);
            order.setSquareOrderId(cloverOrderId);
            order.setTotalAmount(total);
            order.setLocationId(creds.merchantId());
            order.setStoreName(storeName);
            order.setReceiptId(savedReceipt.getId());
            order.setStatus(OrgOrder.OrderStatus.PENDING_PAYMENT);
            orgOrderRepo.save(order);

            log.info("<<< clover pay-at-store order: org={} orderId={} receiptId={}", slug, cloverOrderId, savedReceipt.getId());
            return ResponseEntity.ok(new SquareOrderResponseDTO(
                    cloverOrderId, null, savedReceipt.getId(), total, storeName));

        } catch (Exception e) {
            log.error("!!! clover placeOrder failed org={}: {}", slug, e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Creates a Clover order + payment, saves a Receipt and OrgOrder. */
    @PostMapping("/payments")
    public ResponseEntity<?> createPayment(@PathVariable String slug,
                                           @RequestBody CreateSquareOrderRequest request,
                                           Authentication authentication) {
        log.info("POST /api/organizations/{}/clover/payments items={}", slug,
                request.getItems() == null ? 0 : request.getItems().size());
        try {
            if (request.getSourceId() == null || request.getSourceId().isBlank())
                return ResponseEntity.badRequest().body(Map.of("error", "sourceId is required"));

            CloverApiService.CloverCreds creds = orgService.resolveCloverCreds(slug);

            // 1. Compute total
            BigDecimal total = request.getItems().stream()
                    .map(li -> li.getUnitPrice().multiply(BigDecimal.valueOf(li.getQuantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.HALF_UP);
            long totalCents = total.multiply(BigDecimal.valueOf(100)).longValue();

            // 2. Charge via Clover
            Map<String, Object> result = cloverApiService.createOrderAndChargeForCreds(
                    creds, request.getSourceId(), totalCents, "USD");
            String cloverOrderId   = (String) result.get("orderId");
            String cloverPaymentId = (String) result.get("paymentId");

            // 3. Resolve store name from Clover merchant info
            List<Map<String, Object>> locations = cloverApiService.getLocationsForCreds(creds);
            String storeName = locations.isEmpty() ? "Clover Store" : locations.get(0).get("name").toString();

            // 4. Save personal receipt
            ReceiptDTO receiptDTO = buildReceiptDTO(request, storeName, total);
            ReceiptDTO savedReceipt = receiptService.saveManual(receiptDTO);

            // 5. Save org order record (reusing OrgOrder — squareOrderId/paymentId fields hold Clover ids)
            Organization org = orgRepo.findBySlug(slug).orElseThrow();
            User placedBy = resolveUser(authentication);
            OrgOrder order = new OrgOrder();
            order.setOrg(org);
            order.setPlacedBy(placedBy);
            order.setSquareOrderId(cloverOrderId);
            order.setSquarePaymentId(cloverPaymentId);
            order.setTotalAmount(total);
            order.setLocationId(creds.merchantId());
            order.setStoreName(storeName);
            order.setReceiptId(savedReceipt.getId());
            order.setStatus(OrgOrder.OrderStatus.COMPLETED);
            orgOrderRepo.save(order);

            log.info("<<< clover payment: org={} orderId={} receiptId={}", slug, cloverOrderId, savedReceipt.getId());
            return ResponseEntity.ok(new SquareOrderResponseDTO(
                    cloverOrderId, null, savedReceipt.getId(), total, storeName));

        } catch (Exception e) {
            log.error("!!! clover payment failed org={}: {}", slug, e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ReceiptDTO buildReceiptDTO(CreateSquareOrderRequest request, String storeName, BigDecimal total) {
        ReceiptDTO dto = new ReceiptDTO();
        dto.setStoreName(storeName);
        dto.setStoreType(com.receipttracker.model.StoreType.OTHER);
        dto.setPurchaseDateTime(LocalDateTime.now());
        dto.setSubtotal(total);
        dto.setTotal(total);
        List<ReceiptItemDTO> items = new ArrayList<>();
        for (CreateSquareOrderRequest.CartLineItem li : request.getItems()) {
            ReceiptItemDTO item = new ReceiptItemDTO();
            String name = li.getItemName();
            if (li.getVariationName() != null && !li.getVariationName().isBlank()
                    && !"Default".equalsIgnoreCase(li.getVariationName()))
                name = name + " (" + li.getVariationName() + ")";
            item.setName(name);
            item.setQuantity(li.getQuantity());
            item.setUnitPrice(li.getUnitPrice());
            item.setTotalPrice(li.getUnitPrice().multiply(BigDecimal.valueOf(li.getQuantity())));
            item.setTaxable(false);
            items.add(item);
        }
        dto.setItems(items);
        return dto;
    }

    private User resolveUser(Authentication authentication) {
        if (authentication == null) throw new RuntimeException("Not authenticated");
        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
        String email = oAuth2User.getAttribute("email");
        return userRepo.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }
}
