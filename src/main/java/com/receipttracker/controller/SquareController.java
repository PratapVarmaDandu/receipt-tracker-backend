package com.receipttracker.controller;

import com.receipttracker.dto.*;
import com.receipttracker.model.StoreType;
import com.receipttracker.service.ReceiptService;
import com.receipttracker.service.SquareApiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/square")
public class SquareController {

    private static final Logger log = LoggerFactory.getLogger(SquareController.class);

    @Autowired private SquareApiService squareApiService;
    @Autowired private ReceiptService receiptService;

    // ── Config ────────────────────────────────────────────────────────────────

    /** Returns the Square application ID and location ID needed by the Web Payments SDK. */
    @GetMapping("/config")
    public ResponseEntity<?> getConfig() {
        return ResponseEntity.ok(Map.of(
                "applicationId", squareApiService.getApplicationId(),
                "locationId",    squareApiService.getLocationId(),
                "environment",   squareApiService.getEnvironment()
        ));
    }

    @GetMapping("/locations")
    public ResponseEntity<?> getLocations() {
        log.info("GET /api/square/locations");
        try {
            return ResponseEntity.ok(squareApiService.getLocations());
        } catch (Exception e) {
            log.error("!!! getLocations failed: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Catalog ───────────────────────────────────────────────────────────────

    @GetMapping("/catalog")
    public ResponseEntity<?> getCatalog(@RequestParam(required = false) String cursor) {
        log.info("GET /api/square/catalog cursor={}", cursor);
        try {
            List<SquareCatalogItemDTO> items = squareApiService.getCatalogItems(cursor);
            return ResponseEntity.ok(items);
        } catch (Exception e) {
            log.error("!!! getCatalog failed: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/catalog/categories")
    public ResponseEntity<?> getCategories() {
        log.info("GET /api/square/catalog/categories");
        try {
            return ResponseEntity.ok(squareApiService.getCategories());
        } catch (Exception e) {
            log.error("!!! getCategories failed: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Orders ────────────────────────────────────────────────────────────────

    /**
     * Primary checkout path — uses Square Web Payments SDK token (sourceId) to charge
     * the card directly without leaving the app. Creates the Square order, completes
     * the payment, then records a Receipt in our expense history.
     */
    @PostMapping("/payments")
    public ResponseEntity<?> createPayment(@RequestBody CreateSquareOrderRequest request) {
        log.info("POST /api/square/payments items={} fulfillment={}",
                request.getItems() == null ? 0 : request.getItems().size(),
                request.getFulfillmentType());
        try {
            if (request.getSourceId() == null || request.getSourceId().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "sourceId is required"));
            }

            // 1. Create Square order + charge card
            Map<String, Object> squareResult = squareApiService.createOrderAndCharge(request);
            String squareOrderId = (String) squareResult.get("orderId");
            long   totalCents    = (long)   squareResult.get("totalCents");
            BigDecimal total = BigDecimal.valueOf(totalCents).divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);

            // 2. Resolve seller name from the selected location
            String storeName = squareApiService.getLocationName(request.getLocationId());

            // 3. Save receipt
            ReceiptDTO receiptDTO = buildReceiptDTO(request, storeName, total, squareOrderId);
            ReceiptDTO savedReceipt = receiptService.saveManual(receiptDTO);

            log.info("<<< createPayment - squareOrderId={} receiptId={}", squareOrderId, savedReceipt.getId());
            return ResponseEntity.ok(new SquareOrderResponseDTO(
                    squareOrderId, null, savedReceipt.getId(), total, storeName
            ));

        } catch (Exception e) {
            log.error("!!! createPayment failed: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private ReceiptDTO buildReceiptDTO(CreateSquareOrderRequest request,
                                       String storeName,
                                       BigDecimal subtotal,
                                       String squareOrderId) {
        ReceiptDTO dto = new ReceiptDTO();
        dto.setStoreName(storeName);
        dto.setStoreType(StoreType.OTHER);
        dto.setPurchaseDateTime(LocalDateTime.now());
        dto.setSubtotal(subtotal);
        dto.setTotal(subtotal);

        List<ReceiptItemDTO> items = new ArrayList<>();
        for (CreateSquareOrderRequest.CartLineItem li : request.getItems()) {
            ReceiptItemDTO item = new ReceiptItemDTO();
            String name = li.getItemName();
            if (li.getVariationName() != null && !li.getVariationName().isBlank()
                    && !"Regular".equalsIgnoreCase(li.getVariationName())) {
                name = name + " (" + li.getVariationName() + ")";
            }
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
}
