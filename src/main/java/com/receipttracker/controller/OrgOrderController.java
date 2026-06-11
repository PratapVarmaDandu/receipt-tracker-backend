package com.receipttracker.controller;

import com.receipttracker.dto.*;
import com.receipttracker.model.*;
import com.receipttracker.model.OrgMembership.MemberStatus;
import com.receipttracker.model.OrgMembership.OrgRole;
import com.receipttracker.repository.*;
import com.receipttracker.service.*;
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
 * Org-scoped Square operations: catalog, locations, checkout, order history.
 * All routes require STAFF or higher role in the org.
 */
@RestController
@RequestMapping("/api/organizations/{slug}")
public class OrgOrderController {

    private static final Logger log = LoggerFactory.getLogger(OrgOrderController.class);

    @Autowired private OrganizationService orgService;
    @Autowired private SquareApiService squareApiService;
    @Autowired private ReceiptService receiptService;
    @Autowired private OrgOrderRepository orgOrderRepo;
    @Autowired private OrganizationRepository orgRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private OrgMembershipRepository memberRepo;

    @GetMapping("/catalog")
    public ResponseEntity<?> getCatalog(@PathVariable String slug,
                                        @RequestParam(required = false) String cursor) {
        try {
            SquareApiService.SquareCreds creds = orgService.resolveSquareCreds(slug);
            return ResponseEntity.ok(squareApiService.getCatalogItemsForCreds(creds, cursor));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/locations")
    public ResponseEntity<?> getLocations(@PathVariable String slug) {
        try {
            SquareApiService.SquareCreds creds = orgService.resolveSquareCreds(slug);
            return ResponseEntity.ok(squareApiService.getLocationsForCreds(creds));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/payments")
    public ResponseEntity<?> createPayment(@PathVariable String slug,
                                           @RequestBody CreateSquareOrderRequest request,
                                           Authentication authentication) {
        log.info("POST /api/organizations/{}/payments items={}", slug,
                request.getItems() == null ? 0 : request.getItems().size());
        try {
            if (request.getSourceId() == null || request.getSourceId().isBlank())
                return ResponseEntity.badRequest().body(Map.of("error", "sourceId is required"));

            SquareApiService.SquareCreds creds = orgService.resolveSquareCreds(slug);

            // 1. Create Square order + charge card
            Map<String, Object> squareResult = squareApiService.createOrderAndChargeForCreds(creds, request);
            String squareOrderId = (String) squareResult.get("orderId");
            String squarePaymentId = (String) squareResult.get("paymentId");
            long totalCents = (long) squareResult.get("totalCents");
            BigDecimal total = BigDecimal.valueOf(totalCents).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

            // 2. Resolve seller name
            String storeName = squareApiService.getLocationNameForCreds(creds, request.getLocationId());

            // 3. Save receipt for the placing user
            ReceiptDTO receiptDTO = buildReceiptDTO(request, storeName, total, squareOrderId);
            ReceiptDTO savedReceipt = receiptService.saveManual(receiptDTO);

            // 4. Save org order record
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

            log.info("<<< org payment: org={} orderId={} receiptId={}", slug, squareOrderId, savedReceipt.getId());
            return ResponseEntity.ok(new SquareOrderResponseDTO(
                    squareOrderId, null, savedReceipt.getId(), total, storeName));

        } catch (Exception e) {
            log.error("!!! org payment failed org={}: {}", slug, e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/orders")
    public ResponseEntity<?> listOrders(@PathVariable String slug) {
        try {
            SquareApiService.SquareCreds creds = orgService.resolveSquareCreds(slug); // ensures STAFF+
            Organization org = orgRepo.findBySlug(slug).orElseThrow();
            List<OrgOrderDTO> orders = orgOrderRepo.findByOrgOrderByPlacedAtDesc(org)
                    .stream().map(this::toOrderDTO).collect(Collectors.toList());
            return ResponseEntity.ok(orders);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private OrgOrderDTO toOrderDTO(OrgOrder o) {
        OrgOrderDTO dto = new OrgOrderDTO();
        dto.setId(o.getId());
        dto.setSquareOrderId(o.getSquareOrderId());
        dto.setSquarePaymentId(o.getSquarePaymentId());
        dto.setTotalAmount(o.getTotalAmount());
        dto.setStoreName(o.getStoreName());
        dto.setLocationId(o.getLocationId());
        dto.setReceiptId(o.getReceiptId());
        dto.setStatus(o.getStatus().name());
        dto.setPlacedAt(o.getPlacedAt());
        if (o.getPlacedBy() != null) {
            dto.setPlacedByName(o.getPlacedBy().getName());
            dto.setPlacedByEmail(o.getPlacedBy().getEmail());
        }
        return dto;
    }

    private ReceiptDTO buildReceiptDTO(CreateSquareOrderRequest request, String storeName,
                                       BigDecimal subtotal, String squareOrderId) {
        ReceiptDTO dto = new ReceiptDTO();
        dto.setStoreName(storeName);
        dto.setStoreType(com.receipttracker.model.StoreType.OTHER);
        dto.setPurchaseDateTime(LocalDateTime.now());
        dto.setSubtotal(subtotal);
        dto.setTotal(subtotal);

        List<ReceiptItemDTO> items = new ArrayList<>();
        for (CreateSquareOrderRequest.CartLineItem li : request.getItems()) {
            ReceiptItemDTO item = new ReceiptItemDTO();
            String name = li.getItemName();
            if (li.getVariationName() != null && !li.getVariationName().isBlank()
                    && !"Regular".equalsIgnoreCase(li.getVariationName()))
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
