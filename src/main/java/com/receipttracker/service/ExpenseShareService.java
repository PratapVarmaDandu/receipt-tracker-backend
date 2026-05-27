package com.receipttracker.service;

import com.receipttracker.dto.*;
import com.receipttracker.model.*;
import com.receipttracker.repository.ExpenseShareRepository;
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
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ExpenseShareService {

    private static final Logger log = LoggerFactory.getLogger(ExpenseShareService.class);

    @Autowired private ExpenseShareRepository shareRepo;
    @Autowired private ReceiptRepository receiptRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private EmailService emailService;

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
        log.info(">>> createShares receiptId={} splitType={} count={}",
                receiptId, req.getSplitType(), req.getInvitees().size());

        User inviter = currentUser();
        Receipt receipt = receiptRepo.findById(receiptId)
                .orElseThrow(() -> new RuntimeException("Receipt not found: " + receiptId));

        // Fix: reject demo receipts (user == null) and receipts owned by someone else
        if (receipt.getUser() == null || !receipt.getUser().getId().equals(inviter.getId())) {
            throw new RuntimeException("You do not own this receipt");
        }

        List<ShareInviteItem> invitees = req.getInvitees();
        if (invitees == null || invitees.isEmpty()) {
            throw new RuntimeException("At least one invitee is required");
        }
        if (invitees.size() > 50) {
            throw new RuntimeException("Cannot invite more than 50 people at once");
        }

        // Validate emails and amounts upfront
        for (ShareInviteItem item : invitees) {
            if (item.getEmail() == null || !item.getEmail().contains("@")) {
                throw new RuntimeException("Invalid email address: " + item.getEmail());
            }
            if ("CUSTOM".equalsIgnoreCase(req.getSplitType())) {
                if (item.getAmount() == null || item.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                    throw new RuntimeException("Share amount must be greater than zero for: " + item.getEmail());
                }
            }
        }

        if (receipt.getTotal() == null || receipt.getTotal().compareTo(BigDecimal.ZERO) <= 0) {
            throw new RuntimeException("Receipt has no valid total amount to split");
        }

        boolean isEqual = "EQUAL".equalsIgnoreCase(req.getSplitType());
        BigDecimal equalAmount = isEqual
                ? receipt.getTotal().divide(BigDecimal.valueOf(invitees.size()), 2, RoundingMode.HALF_UP)
                : null;

        // Collect existing invitee emails for this receipt to prevent duplicates
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
            share.setStatus(ShareStatus.PENDING);
            return shareRepo.save(share);
        }).collect(Collectors.toList());

        created.forEach(share -> {
            String tokenUrl = frontendUrl + "/share/" + share.getInviteToken();
            emailService.sendInvite(share.getInviteeEmail(), receipt.getStoreName(), inviter.getName(), share.getShareAmount(), tokenUrl);
        });

        log.info("<<< createShares created={} shares for receiptId={}", created.size(), receiptId);
        return created.stream().map(this::toDTO).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ExpenseShareDTO> getSharesForReceipt(Long receiptId) {
        log.trace(">>> getSharesForReceipt receiptId={}", receiptId);
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

        List<ReceiptItemDTO> items = receipt.getItems().stream().map(item -> {
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
        dto.setItems(items);

        log.debug("<<< getShareByToken token={} status={}", token, share.getStatus());
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
            case "ACCEPT" -> share.setStatus(ShareStatus.ACCEPTED);
            case "DENY"   -> share.setStatus(ShareStatus.DENIED);
            case "REQUEST_CHANGE" -> {
                if (req.getCounterAmount() == null || req.getCounterAmount().compareTo(BigDecimal.ZERO) <= 0) {
                    throw new RuntimeException("counterAmount must be greater than zero");
                }
                share.setStatus(ShareStatus.CHANGE_REQUESTED);
                share.setCounterAmount(req.getCounterAmount());
                share.setCounterNote(req.getCounterNote());
                String tokenUrl = frontendUrl + "/share/" + token;
                emailService.sendChangeRequest(
                        share.getInviter().getEmail(),
                        caller.getEmail(),
                        req.getCounterAmount(),
                        req.getCounterNote(),
                        tokenUrl
                );
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
        emailService.sendOwnerDecision(
                share.getInviteeEmail(),
                "APPROVE".equalsIgnoreCase(action),
                share.getShareAmount(),
                req.getResponseNote(),
                tokenUrl
        );

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
        dto.setCreatedAt(share.getCreatedAt());
        dto.setUpdatedAt(share.getUpdatedAt());
        return dto;
    }
}
