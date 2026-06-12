package com.receipttracker.controller;

import com.receipttracker.dto.CreateSquareOrderRequest;
import com.receipttracker.dto.ReceiptDTO;
import com.receipttracker.dto.ReceiptItemDTO;
import com.receipttracker.dto.SquareOrderResponseDTO;
import com.receipttracker.model.OrgOrder;
import com.receipttracker.model.Organization;
import com.receipttracker.model.User;
import com.receipttracker.repository.OrgOrderRepository;
import com.receipttracker.repository.OrganizationRepository;
import com.receipttracker.repository.UserRepository;
import com.receipttracker.service.CloverApiService;
import com.receipttracker.service.OrganizationService;
import com.receipttracker.service.ReceiptService;
import com.receipttracker.service.SquareApiService;
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
import java.util.stream.Collectors;

/**
 * Public storefront endpoints — no org membership required.
 * GET  /api/shop/public                   — list all public orgs
 * GET  /api/shop/public/{slug}/square/locations
 * GET  /api/shop/public/{slug}/square/catalog
 * POST /api/shop/public/{slug}/square/payments  (auth required)
 * GET  /api/shop/public/{slug}/clover/locations
 * GET  /api/shop/public/{slug}/clover/catalog
 * POST /api/shop/public/{slug}/clover/orders    (auth required)
 */
@RestController
@RequestMapping("/api/shop/public")
public class PublicShopController {

    private static final Logger log = LoggerFactory.getLogger(PublicShopController.class);

    @Autowired private OrganizationRepository orgRepo;
    @Autowired private OrganizationService orgService;
    @Autowired private SquareApiService squareApiService;
    @Autowired private CloverApiService cloverApiService;
    @Autowired private ReceiptService receiptService;
    @Autowired private OrgOrderRepository orgOrderRepo;
    @Autowired private UserRepository userRepo;

    /** Lists all orgs that have publicStore=true (no auth). */
    @GetMapping
    public ResponseEntity<?> listPublicStores() {
        List<Organization> orgs = orgRepo.findAllByPublicStoreTrue();
        List<Map<String, Object>> result = orgs.stream()
                .filter(o -> o.getStatus() == Organization.OrgStatus.ACTIVE)
                .map(o -> Map.<String, Object>of(
                        "slug",            o.getSlug(),
                        "name",            o.getName(),
                        "squareConfigured", o.isSquareConfigured(),
                        "cloverConfigured", o.isCloverConfigured()
                ))
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    // ── Square ────────────────────────────────────────────────────────────────

    /** Returns the non-secret Square config needed to initialise the Web Payments SDK. */
    @GetMapping("/{slug}/square/config")
    public ResponseEntity<?> squareConfig(@PathVariable String slug) {
        try {
            SquareApiService.SquareCreds creds = orgService.resolvePublicSquareCreds(slug);
            return ResponseEntity.ok(Map.of(
                    "configured",    true,
                    "applicationId", creds.applicationId() != null ? creds.applicationId() : "",
                    "locationId",    creds.locationId()    != null ? creds.locationId()    : "",
                    "environment",   creds.environment()
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage(), "configured", false));
        }
    }

    @GetMapping("/{slug}/square/locations")
    public ResponseEntity<?> squareLocations(@PathVariable String slug) {
        try {
            SquareApiService.SquareCreds creds = orgService.resolvePublicSquareCreds(slug);
            return ResponseEntity.ok(squareApiService.getLocationsForCreds(creds));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{slug}/square/catalog")
    public ResponseEntity<?> squareCatalog(@PathVariable String slug) {
        try {
            SquareApiService.SquareCreds creds = orgService.resolvePublicSquareCreds(slug);
            return ResponseEntity.ok(squareApiService.getCatalogItemsForCreds(creds, null));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{slug}/square/payments")
    public ResponseEntity<?> squarePayment(@PathVariable String slug,
                                           @RequestBody CreateSquareOrderRequest request,
                                           Authentication authentication) {
        log.info("POST /api/shop/public/{}/square/payments items={}", slug,
                request.getItems() == null ? 0 : request.getItems().size());
        try {
            SquareApiService.SquareCreds creds = orgService.resolvePublicSquareCreds(slug);

            BigDecimal total = request.getItems().stream()
                    .map(li -> li.getUnitPrice().multiply(BigDecimal.valueOf(li.getQuantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.HALF_UP);

            Map<String, Object> result = squareApiService.createOrderAndChargeForCreds(creds, request);
            String squareOrderId   = (String) result.get("orderId");
            String squarePaymentId = (String) result.get("paymentId");

            List<Map<String, Object>> locations = squareApiService.getLocationsForCreds(creds);
            String storeName = locations.isEmpty() ? "Square Store" : locations.get(0).get("name").toString();

            ReceiptDTO receiptDTO = buildReceiptDTO(request, storeName, total);
            ReceiptDTO savedReceipt = receiptService.saveManual(receiptDTO);

            Organization org = orgRepo.findBySlug(slug).orElseThrow();
            User placedBy = resolveUser(authentication);
            OrgOrder order = new OrgOrder();
            order.setOrg(org);
            order.setPlacedBy(placedBy);
            order.setSquareOrderId(squareOrderId);
            order.setSquarePaymentId(squarePaymentId);
            order.setTotalAmount(total);
            order.setLocationId(request.getLocationId());
            order.setStoreName(storeName);
            order.setReceiptId(savedReceipt.getId());
            order.setStatus(OrgOrder.OrderStatus.COMPLETED);
            orgOrderRepo.save(order);

            log.info("<<< public square payment: org={} orderId={} receiptId={}", slug, squareOrderId, savedReceipt.getId());
            return ResponseEntity.ok(new SquareOrderResponseDTO(
                    squareOrderId, squarePaymentId, savedReceipt.getId(), total, storeName));
        } catch (Exception e) {
            log.error("!!! public square payment failed org={}: {}", slug, e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Clover ────────────────────────────────────────────────────────────────

    @GetMapping("/{slug}/clover/locations")
    public ResponseEntity<?> cloverLocations(@PathVariable String slug) {
        try {
            CloverApiService.CloverCreds creds = orgService.resolvePublicCloverCreds(slug);
            return ResponseEntity.ok(cloverApiService.getLocationsForCreds(creds));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{slug}/clover/catalog")
    public ResponseEntity<?> cloverCatalog(@PathVariable String slug) {
        try {
            CloverApiService.CloverCreds creds = orgService.resolvePublicCloverCreds(slug);
            return ResponseEntity.ok(cloverApiService.getCatalogItemsForCreds(creds));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{slug}/clover/orders")
    public ResponseEntity<?> cloverOrder(@PathVariable String slug,
                                         @RequestBody CreateSquareOrderRequest request,
                                         Authentication authentication) {
        log.info("POST /api/shop/public/{}/clover/orders items={}", slug,
                request.getItems() == null ? 0 : request.getItems().size());
        try {
            CloverApiService.CloverCreds creds = orgService.resolvePublicCloverCreds(slug);

            BigDecimal total = request.getItems().stream()
                    .map(li -> li.getUnitPrice().multiply(BigDecimal.valueOf(li.getQuantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.HALF_UP);

            Map<String, Object> result = cloverApiService.createOrderForCreds(creds, "USD");
            String cloverOrderId = (String) result.get("orderId");

            List<Map<String, Object>> locations = cloverApiService.getLocationsForCreds(creds);
            String storeName = locations.isEmpty() ? "Clover Store" : locations.get(0).get("name").toString();

            ReceiptDTO receiptDTO = buildReceiptDTO(request, storeName, total);
            ReceiptDTO savedReceipt = receiptService.saveManual(receiptDTO);

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

            log.info("<<< public clover order: org={} orderId={} receiptId={}", slug, cloverOrderId, savedReceipt.getId());
            return ResponseEntity.ok(new SquareOrderResponseDTO(
                    cloverOrderId, null, savedReceipt.getId(), total, storeName));
        } catch (Exception e) {
            log.error("!!! public clover order failed org={}: {}", slug, e.getMessage(), e);
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
        if (authentication == null) throw new RuntimeException("Authentication required to place an order");
        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
        String email = oAuth2User.getAttribute("email");
        return userRepo.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }
}
