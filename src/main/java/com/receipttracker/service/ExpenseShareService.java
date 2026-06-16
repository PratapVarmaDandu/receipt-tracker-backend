package com.receipttracker.service;

import com.receipttracker.dto.*;
import com.receipttracker.model.*;
import com.receipttracker.repository.ExpenseShareItemRepository;
import com.receipttracker.repository.ExpenseShareRepository;
import com.receipttracker.repository.ReceiptItemRepository;
import com.receipttracker.repository.ReceiptRepository;
import com.receipttracker.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ExpenseShareService {

    private static final Logger log = LoggerFactory.getLogger(ExpenseShareService.class);

    @Autowired private ExpenseShareRepository shareRepo;
    @Autowired private ExpenseShareItemRepository shareItemRepo;
    @Autowired private ReceiptRepository receiptRepo;
    @Autowired private ReceiptItemRepository receiptItemRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private EmailService emailService;
    @Autowired private FeatureEntitlementService entitlement;

    @Value("${app.frontend.url:http://localhost:4200}")
    private String frontendUrl;

    // ── User resolution ───────────────────────────────────────────────────────

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        OAuth2User principal = (OAuth2User) auth.getPrincipal();
        String googleId = principal.getAttribute("sub");
        return userRepo.findByGoogleId(googleId)
                .orElseThrow(() -> new RuntimeException("Authenticated user not found"));
    }

    // ── Public API ────────────────────────────────────────────────────────────

    @Transactional
    public List<ExpenseShareDTO> createShares(Long receiptId, CreateShareRequest req) {
        log.info(">>> createShares receiptId={} splitType={}", receiptId, req.getSplitType());
        entitlement.requireFeature(AppFeature.EXPENSE_SHARING);

        User inviter = currentUser();
        Receipt receipt = receiptRepo.findById(receiptId)
                .orElseThrow(() -> new RuntimeException("Receipt not found: " + receiptId));

        if (receipt.getUser() == null || !receipt.getUser().getId().equals(inviter.getId())) {
            throw new RuntimeException("You do not own this receipt");
        }

        if (receipt.getTotal() == null || receipt.getTotal().compareTo(BigDecimal.ZERO) <= 0) {
            throw new RuntimeException("Receipt has no valid total amount to split");
        }

        String splitType = req.getSplitType();

        if ("ITEM_BASED".equalsIgnoreCase(splitType)) {
            return createItemBasedShares(receipt, inviter, req);
        }

        if ("PAID_FOR_ME".equalsIgnoreCase(splitType)) {
            return createPaidForMeShare(receipt, inviter, req);
        }

        // ── EQUAL / CUSTOM path (unchanged logic) ────────────────────────────
        List<ShareInviteItem> invitees = req.getInvitees();
        if (invitees == null || invitees.isEmpty()) {
            throw new RuntimeException("At least one invitee is required");
        }
        if (invitees.size() > 50) {
            throw new RuntimeException("Cannot invite more than 50 people at once");
        }

        for (ShareInviteItem item : invitees) {
            if (item.getEmail() == null || !item.getEmail().contains("@")) {
                throw new RuntimeException("Invalid email address: " + item.getEmail());
            }
            if ("CUSTOM".equalsIgnoreCase(splitType)) {
                if (item.getAmount() == null || item.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                    throw new RuntimeException("Share amount must be greater than zero for: " + item.getEmail());
                }
            }
        }

        boolean isEqual = "EQUAL".equalsIgnoreCase(splitType);
        BigDecimal equalAmount = isEqual
                ? receipt.getTotal().divide(BigDecimal.valueOf(invitees.size() + 1), 2, RoundingMode.HALF_UP)
                : null;

        Set<String> alreadyInvited = shareRepo.findByReceiptId(receiptId).stream()
                .filter(s -> s.getStatus() == ShareStatus.PENDING
                          || s.getStatus() == ShareStatus.CHANGE_REQUESTED
                          || s.getStatus() == ShareStatus.CHANGE_APPROVED)
                .map(s -> s.getInviteeEmail().toLowerCase())
                .collect(Collectors.toSet());

        List<ExpenseShare> created = invitees.stream().map(item -> {
            String email = item.getEmail().trim().toLowerCase();
            if (alreadyInvited.contains(email)) {
                throw new RuntimeException("An active invite already exists for: " + email);
            }
            BigDecimal amount = isEqual ? equalAmount : item.getAmount();
            ExpenseShare share = new ExpenseShare();
            share.setReceipt(receipt);
            share.setInviter(inviter);
            share.setInviteeEmail(email);
            share.setShareAmount(amount);
            share.setSplitType(splitType.toUpperCase());
            share.setStatus(ShareStatus.PENDING);
            return shareRepo.save(share);
        }).collect(Collectors.toList());

        created.forEach(share -> {
            String tokenUrl = frontendUrl + "/share/" + share.getInviteToken();
            emailService.sendInvite(share.getInviteeEmail(), receipt.getStoreName(),
                    inviter.getName(), share.getShareAmount(), tokenUrl);
        });

        log.info("<<< createShares created={} {} shares for receiptId={}", created.size(), splitType, receiptId);
        return created.stream().map(this::toDTO).collect(Collectors.toList());
    }

    // ── Item-based split ──────────────────────────────────────────────────────

    private List<ExpenseShareDTO> createItemBasedShares(Receipt receipt, User inviter, CreateShareRequest req) {
        List<ItemAssignment> assignments = req.getItemAssignments();
        if (assignments == null || assignments.isEmpty()) {
            throw new RuntimeException("itemAssignments required for ITEM_BASED split");
        }
        if (assignments.size() > 50) {
            throw new RuntimeException("Cannot invite more than 50 people at once");
        }

        // Build map of valid receipt item ids → item entity
        Map<Long, ReceiptItem> receiptItems = receipt.getItems().stream()
                .collect(Collectors.toMap(ReceiptItem::getId, i -> i));

        // Effective tax rate from the receipt itself
        BigDecimal taxRate = BigDecimal.ZERO;
        if (receipt.getSubtotal() != null && receipt.getTax() != null
                && receipt.getSubtotal().compareTo(BigDecimal.ZERO) > 0) {
            taxRate = receipt.getTax().divide(receipt.getSubtotal(), 6, RoundingMode.HALF_UP);
        }
        final BigDecimal effectiveTaxRate = taxRate;

        Set<String> alreadyInvited = shareRepo.findByReceiptId(receipt.getId()).stream()
                .filter(s -> s.getStatus() == ShareStatus.PENDING
                          || s.getStatus() == ShareStatus.CHANGE_REQUESTED
                          || s.getStatus() == ShareStatus.CHANGE_APPROVED)
                .map(s -> s.getInviteeEmail().toLowerCase())
                .collect(Collectors.toSet());

        // Count how many invitees are assigned each item (for shared-item price splitting)
        Map<Long, Long> itemAssigneeCount = assignments.stream()
                .filter(a -> a.getItemIds() != null)
                .flatMap(a -> a.getItemIds().stream())
                .collect(Collectors.groupingBy(id -> id, Collectors.counting()));

        List<ExpenseShare> created = new ArrayList<>();

        for (ItemAssignment assignment : assignments) {
            if (assignment.getEmail() == null || !assignment.getEmail().contains("@")) {
                throw new RuntimeException("Invalid email: " + assignment.getEmail());
            }
            if (assignment.getItemIds() == null || assignment.getItemIds().isEmpty()) {
                throw new RuntimeException("No items assigned to: " + assignment.getEmail());
            }

            String email = assignment.getEmail().trim().toLowerCase();
            if (alreadyInvited.contains(email)) {
                throw new RuntimeException("An active invite already exists for: " + email);
            }

            // Validate all itemIds belong to this receipt and compute totals
            BigDecimal itemSubtotal = BigDecimal.ZERO;
            BigDecimal itemTax = BigDecimal.ZERO;
            List<ExpenseShareItem> shareItems = new ArrayList<>();

            for (Long itemId : assignment.getItemIds()) {
                ReceiptItem ri = receiptItems.get(itemId);
                if (ri == null) {
                    throw new RuntimeException("Item " + itemId + " does not belong to receipt " + receipt.getId());
                }
                BigDecimal price = ri.getTotalPrice() != null ? ri.getTotalPrice() : BigDecimal.ZERO;
                long assignees = itemAssigneeCount.getOrDefault(itemId, 1L);
                BigDecimal splitPrice = assignees > 1
                        ? price.divide(BigDecimal.valueOf(assignees), 2, RoundingMode.HALF_UP)
                        : price;
                BigDecimal taxForItem = ri.isTaxable()
                        ? splitPrice.multiply(effectiveTaxRate).setScale(2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;

                itemSubtotal = itemSubtotal.add(splitPrice);
                itemTax = itemTax.add(taxForItem);

                ExpenseShareItem si = new ExpenseShareItem();
                si.setReceiptItem(ri);
                si.setItemTotal(splitPrice);
                si.setTaxAmount(taxForItem);
                si.setTaxRate(effectiveTaxRate);
                shareItems.add(si);
            }

            BigDecimal shareTotal = itemSubtotal.add(itemTax).setScale(2, RoundingMode.HALF_UP);

            ExpenseShare share = new ExpenseShare();
            share.setReceipt(receipt);
            share.setInviter(inviter);
            share.setInviteeEmail(email);
            share.setShareAmount(shareTotal);
            share.setSplitType("ITEM_BASED");
            share.setStatus(ShareStatus.PENDING);
            ExpenseShare savedShare = shareRepo.save(share);

            // Link share items now that the share has an id
            for (ExpenseShareItem si : shareItems) {
                si.setShare(savedShare);
                shareItemRepo.save(si);
            }
            created.add(savedShare);

            String tokenUrl = frontendUrl + "/share/" + savedShare.getInviteToken();
            emailService.sendInvite(email, receipt.getStoreName(), inviter.getName(), shareTotal, tokenUrl);
        }

        log.info("<<< createShares (ITEM_BASED) created={} shares for receiptId={}", created.size(), receipt.getId());
        return created.stream().map(this::toDTO).collect(Collectors.toList());
    }

    private List<ExpenseShareDTO> createPaidForMeShare(Receipt receipt, User inviter, CreateShareRequest req) {
        List<ShareInviteItem> invitees = req.getInvitees();
        if (invitees == null || invitees.size() != 1) {
            throw new RuntimeException("PAID_FOR_ME split requires exactly one payer email");
        }
        ShareInviteItem payerItem = invitees.get(0);
        if (payerItem.getEmail() == null || !payerItem.getEmail().contains("@")) {
            throw new RuntimeException("Invalid payer email address");
        }
        if (payerItem.getAmount() == null || payerItem.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new RuntimeException("Amount owed must be greater than zero");
        }

        String payerEmail = payerItem.getEmail().trim().toLowerCase();

        boolean alreadyExists = shareRepo.findByReceiptId(receipt.getId()).stream()
                .anyMatch(s -> s.getInviteeEmail().equalsIgnoreCase(payerEmail)
                        && (s.getStatus() == ShareStatus.PENDING
                            || s.getStatus() == ShareStatus.CHANGE_REQUESTED
                            || s.getStatus() == ShareStatus.CHANGE_APPROVED));
        if (alreadyExists) {
            throw new RuntimeException("An active record already exists for: " + payerEmail);
        }

        ExpenseShare share = new ExpenseShare();
        share.setReceipt(receipt);
        share.setInviter(inviter);
        share.setInviteeEmail(payerEmail);
        share.setShareAmount(payerItem.getAmount());
        share.setSplitType("PAID_FOR_ME");
        share.setPaidForOwner(true);
        share.setStatus(ShareStatus.PENDING);
        ExpenseShare saved = shareRepo.save(share);

        String tokenUrl = frontendUrl + "/share/" + saved.getInviteToken();
        emailService.sendInvite(payerEmail, receipt.getStoreName(), inviter.getName(), payerItem.getAmount(), tokenUrl);

        log.info("<<< createPaidForMeShare for receiptId={} payer={}", receipt.getId(), payerEmail);
        return List.of(toDTO(saved));
    }

    @Transactional(readOnly = true)
    public List<ExpenseShareDTO> getSharesForReceipt(Long receiptId) {
        log.trace(">>> getSharesForReceipt receiptId={}", receiptId);
        entitlement.requireFeature(AppFeature.EXPENSE_SHARING);
        User caller = currentUser();
        Receipt receipt = receiptRepo.findById(receiptId)
                .orElseThrow(() -> new RuntimeException("Receipt not found: " + receiptId));

        if (receipt.getUser() == null || !receipt.getUser().getId().equals(caller.getId())) {
            throw new RuntimeException("You do not own this receipt");
        }

        List<ExpenseShareDTO> result = shareRepo.findByReceiptId(receiptId).stream()
                .map(this::toDTO).collect(Collectors.toList());
        log.debug("<<< getSharesForReceipt receiptId={} count={}", receiptId, result.size());
        return result;
    }

    @Transactional(readOnly = true)
    public ShareViewDTO getShareByToken(String token) {
        log.trace(">>> getShareByToken");
        ExpenseShare share = shareRepo.findByInviteToken(token)
                .orElseThrow(() -> new RuntimeException("Invalid or expired invite link"));

        Receipt receipt = share.getReceipt();
        ShareViewDTO dto = new ShareViewDTO();
        dto.setStoreName(receipt.getStoreName());
        dto.setOwnerName(share.getInviter().getName());
        dto.setReceiptTotal(receipt.getTotal());
        dto.setShareAmount(share.getShareAmount());
        dto.setCounterAmount(share.getCounterAmount());
        dto.setPurchaseDateTime(receipt.getPurchaseDateTime());
        dto.setStatus(share.getStatus());
        dto.setShareNote(share.getShareNote());
        dto.setCounterNote(share.getCounterNote());
        dto.setChangeResponseNote(share.getChangeResponseNote());
        dto.setInviteeLinkNeeded(share.getInvitee() == null);
        dto.setSplitType(share.getSplitType());
        dto.setPaidForOwner(share.isPaidForOwner());

        // All receipt items (for context display)
        List<ReceiptItemDTO> allItems = receipt.getItems().stream().map(item -> {
            ReceiptItemDTO idto = new ReceiptItemDTO();
            idto.setId(item.getId());
            idto.setName(item.getName());
            idto.setDescription(item.getDescription());
            idto.setQuantity(item.getQuantity());
            idto.setUnitPrice(item.getUnitPrice());
            idto.setTotalPrice(item.getTotalPrice());
            idto.setCategory(item.getCategory());
            idto.setTaxable(item.isTaxable());
            return idto;
        }).collect(Collectors.toList());
        dto.setItems(allItems);

        // For ITEM_BASED: populate assigned-item breakdown
        if ("ITEM_BASED".equalsIgnoreCase(share.getSplitType())) {
            List<ExpenseShareItem> shareItems = shareItemRepo.findByShare(share);
            List<ExpenseShareItemDTO> assigned = shareItems.stream().map(this::toShareItemDTO)
                    .collect(Collectors.toList());
            dto.setAssignedItems(assigned);

            BigDecimal sub = shareItems.stream()
                    .map(ExpenseShareItem::getItemTotal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal tax = shareItems.stream()
                    .map(ExpenseShareItem::getTaxAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            dto.setItemSubtotal(sub.setScale(2, RoundingMode.HALF_UP));
            dto.setItemTax(tax.setScale(2, RoundingMode.HALF_UP));
        }

        log.debug("<<< getShareByToken splitType={} status={}", share.getSplitType(), share.getStatus());
        return dto;
    }

    @Transactional
    public ExpenseShareDTO processInviteeAction(String token, InviteeActionRequest req) {
        log.info(">>> processInviteeAction action={}", req.getAction());
        User caller = currentUser();

        ExpenseShare share = shareRepo.findByInviteToken(token)
                .orElseThrow(() -> new RuntimeException("Invalid or expired invite link"));

        if (!share.getInviteeEmail().equalsIgnoreCase(caller.getEmail())) {
            throw new RuntimeException("This invite is not for your account");
        }

        ShareStatus current = share.getStatus();
        if (current != ShareStatus.PENDING && current != ShareStatus.CHANGE_REJECTED) {
            throw new RuntimeException("Action not allowed in status: " + current);
        }

        if (share.getInvitee() == null) {
            share.setInvitee(caller);
        }

        String action = req.getAction();
        switch (action.toUpperCase()) {
            case "ACCEPT" -> {
                share.setStatus(ShareStatus.ACCEPTED);
                String receiptUrl = frontendUrl + "/receipts/" + share.getReceipt().getId();
                emailService.sendInviteeDecision(share.getInviter().getEmail(), caller.getEmail(),
                        "ACCEPT", share.getShareAmount(), receiptUrl);
            }
            case "DENY" -> {
                share.setStatus(ShareStatus.DENIED);
                String receiptUrl = frontendUrl + "/receipts/" + share.getReceipt().getId();
                emailService.sendInviteeDecision(share.getInviter().getEmail(), caller.getEmail(),
                        "DENY", share.getShareAmount(), receiptUrl);
            }
            case "REQUEST_CHANGE" -> {
                if (req.getCounterAmount() == null || req.getCounterAmount().compareTo(BigDecimal.ZERO) <= 0) {
                    throw new RuntimeException("counterAmount must be greater than zero");
                }
                share.setStatus(ShareStatus.CHANGE_REQUESTED);
                share.setCounterAmount(req.getCounterAmount());
                share.setCounterNote(req.getCounterNote());
                String tokenUrl = frontendUrl + "/share/" + token;
                emailService.sendChangeRequest(share.getInviter().getEmail(), caller.getEmail(),
                        req.getCounterAmount(), req.getCounterNote(), tokenUrl);
            }
            default -> throw new RuntimeException("Unknown action: " + action);
        }

        ExpenseShare saved = shareRepo.save(share);
        log.info("<<< processInviteeAction token={} newStatus={}", token, saved.getStatus());
        return toDTO(saved);
    }

    @Transactional
    public ExpenseShareDTO processOwnerAction(Long shareId, OwnerActionRequest req) {
        log.info(">>> processOwnerAction shareId={} action={}", shareId, req.getAction());
        entitlement.requireFeature(AppFeature.EXPENSE_SHARING);
        User caller = currentUser();

        ExpenseShare share = shareRepo.findById(shareId)
                .orElseThrow(() -> new RuntimeException("Share not found: " + shareId));

        if (!share.getInviter().getId().equals(caller.getId())) {
            throw new RuntimeException("You are not the owner of this share");
        }

        if (share.getStatus() != ShareStatus.CHANGE_REQUESTED) {
            throw new RuntimeException("Owner action only allowed when status is CHANGE_REQUESTED");
        }

        String action = req.getAction();
        switch (action.toUpperCase()) {
            case "APPROVE" -> {
                if (share.getCounterAmount() == null) {
                    throw new RuntimeException("No counter-offer amount to approve");
                }
                share.setShareAmount(share.getCounterAmount());
                share.setStatus(ShareStatus.CHANGE_APPROVED);
            }
            case "REJECT" -> {
                share.setChangeResponseNote(req.getResponseNote());
                share.setStatus(ShareStatus.CHANGE_REJECTED);
            }
            default -> throw new RuntimeException("Unknown action: " + action);
        }

        String tokenUrl = frontendUrl + "/share/" + share.getInviteToken();
        emailService.sendOwnerDecision(share.getInviteeEmail(), "APPROVE".equalsIgnoreCase(action),
                share.getShareAmount(), req.getResponseNote(), tokenUrl);

        ExpenseShare saved = shareRepo.save(share);
        log.info("<<< processOwnerAction shareId={} newStatus={}", shareId, saved.getStatus());
        return toDTO(saved);
    }

    @Transactional(readOnly = true)
    public List<ExpenseShareDTO> getMyShares() {
        log.trace(">>> getMyShares");
        User caller = currentUser();
        List<ExpenseShareDTO> result = shareRepo.findByInviteeEmail(caller.getEmail()).stream()
                .map(this::toDTO).collect(Collectors.toList());
        log.debug("<<< getMyShares count={}", result.size());
        return result;
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private ExpenseShareDTO toDTO(ExpenseShare share) {
        ExpenseShareDTO dto = new ExpenseShareDTO();
        dto.setId(share.getId());
        dto.setReceiptId(share.getReceipt().getId());
        dto.setStoreName(share.getReceipt().getStoreName());
        dto.setInviteeEmail(share.getInviteeEmail());
        dto.setInviteeLinked(share.getInvitee() != null);
        dto.setShareAmount(share.getShareAmount());
        dto.setCounterAmount(share.getCounterAmount());
        dto.setShareNote(share.getShareNote());
        dto.setCounterNote(share.getCounterNote());
        dto.setChangeResponseNote(share.getChangeResponseNote());
        dto.setStatus(share.getStatus());
        dto.setInviteToken(share.getInviteToken());
        dto.setSplitType(share.getSplitType());
        dto.setPaidForOwner(share.isPaidForOwner());
        dto.setCreatedAt(share.getCreatedAt());
        dto.setUpdatedAt(share.getUpdatedAt());

        if ("ITEM_BASED".equalsIgnoreCase(share.getSplitType()) && share.getItems() != null) {
            dto.setItems(share.getItems().stream().map(this::toShareItemDTO).collect(Collectors.toList()));
        }
        return dto;
    }

    private ExpenseShareItemDTO toShareItemDTO(ExpenseShareItem si) {
        ExpenseShareItemDTO dto = new ExpenseShareItemDTO();
        dto.setReceiptItemId(si.getReceiptItem().getId());
        dto.setItemName(si.getReceiptItem().getName());
        dto.setItemTotal(si.getItemTotal());
        dto.setTaxAmount(si.getTaxAmount());
        dto.setTaxRate(si.getTaxRate());
        dto.setTaxable(si.getReceiptItem().isTaxable());
        return dto;
    }
}
