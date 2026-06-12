package com.receipttracker.service;

import com.receipttracker.dto.CreateShareRequest;
import com.receipttracker.dto.ExpenseShareDTO;
import com.receipttracker.dto.ItemAssignment;
import com.receipttracker.dto.ShareInviteItem;
import com.receipttracker.model.*;
import com.receipttracker.repository.ExpenseShareItemRepository;
import com.receipttracker.repository.ExpenseShareRepository;
import com.receipttracker.repository.ReceiptItemRepository;
import com.receipttracker.repository.ReceiptRepository;
import com.receipttracker.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExpenseShareServiceTest {

    @Mock private ExpenseShareRepository shareRepo;
    @Mock private ExpenseShareItemRepository shareItemRepo;
    @Mock private ReceiptRepository receiptRepo;
    @Mock private ReceiptItemRepository receiptItemRepo;
    @Mock private UserRepository userRepo;
    @Mock private EmailService emailService;
    @Mock private FeatureEntitlementService entitlement;

    @InjectMocks
    private ExpenseShareService service;

    private User owner;
    private Receipt receipt;
    private ReceiptItem taxableItem;
    private ReceiptItem nonTaxableItem;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "frontendUrl", "http://localhost:4200");

        owner = new User();
        owner.setId(1L);
        owner.setGoogleId("google-owner");
        owner.setEmail("owner@example.com");
        owner.setName("Owner");

        taxableItem = new ReceiptItem();
        taxableItem.setId(10L);
        taxableItem.setName("Apple");
        taxableItem.setTotalPrice(new BigDecimal("5.00"));
        taxableItem.setTaxable(true);

        nonTaxableItem = new ReceiptItem();
        nonTaxableItem.setId(11L);
        nonTaxableItem.setName("Bread");
        nonTaxableItem.setTotalPrice(new BigDecimal("3.00"));
        nonTaxableItem.setTaxable(false);

        receipt = new Receipt();
        receipt.setId(100L);
        receipt.setUser(owner);
        receipt.setStoreName("Test Store");
        receipt.setSubtotal(new BigDecimal("8.00"));
        receipt.setTax(new BigDecimal("0.40")); // 5% effective rate
        receipt.setTotal(new BigDecimal("8.40"));
        receipt.setItems(new ArrayList<>(List.of(taxableItem, nonTaxableItem)));
        taxableItem.setReceipt(receipt);
        nonTaxableItem.setReceipt(receipt);

        mockSecurityContext("google-owner");
        when(userRepo.findByGoogleId("google-owner")).thenReturn(Optional.of(owner));
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private void mockSecurityContext(String googleId) {
        OAuth2User principal = mock(OAuth2User.class);
        when(principal.getAttribute("sub")).thenReturn(googleId);
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(principal);
        SecurityContext ctx = mock(SecurityContext.class);
        when(ctx.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(ctx);
    }

    private ExpenseShare mockSavedShare(Long id, String email, BigDecimal amount, String splitType) {
        ExpenseShare share = new ExpenseShare();
        share.setId(id);
        share.setReceipt(receipt);
        share.setInviter(owner);
        share.setInviteeEmail(email);
        share.setShareAmount(amount);
        share.setSplitType(splitType);
        share.setStatus(ShareStatus.PENDING);
        share.setItems(new ArrayList<>());
        return share;
    }

    // ── EQUAL split ───────────────────────────────────────────────────────────

    @Test
    void createEqualShares_dividesTotalByInviteePlusOne() {
        when(receiptRepo.findById(100L)).thenReturn(Optional.of(receipt));
        when(shareRepo.findByReceiptId(100L)).thenReturn(Collections.emptyList());

        ExpenseShare saved = mockSavedShare(1L, "alice@example.com", new BigDecimal("4.20"), "EQUAL");
        when(shareRepo.save(any())).thenReturn(saved);

        CreateShareRequest req = new CreateShareRequest();
        req.setSplitType("EQUAL");
        req.setInvitees(List.of(invitee("alice@example.com", null)));

        List<ExpenseShareDTO> result = service.createShares(100L, req);

        assertThat(result).hasSize(1);
        // total 8.40 / 2 people = 4.20
        ArgumentCaptor<ExpenseShare> captor = ArgumentCaptor.forClass(ExpenseShare.class);
        verify(shareRepo).save(captor.capture());
        assertThat(captor.getValue().getShareAmount()).isEqualByComparingTo("4.20");
        verify(emailService).sendInvite(eq("alice@example.com"), any(), any(), any(), any());
    }

    @Test
    void createEqualShares_twoInvitees_dividesCorrectly() {
        when(receiptRepo.findById(100L)).thenReturn(Optional.of(receipt));
        when(shareRepo.findByReceiptId(100L)).thenReturn(Collections.emptyList());

        ExpenseShare s1 = mockSavedShare(1L, "alice@example.com", new BigDecimal("2.80"), "EQUAL");
        ExpenseShare s2 = mockSavedShare(2L, "bob@example.com", new BigDecimal("2.80"), "EQUAL");
        when(shareRepo.save(any())).thenReturn(s1, s2);

        CreateShareRequest req = new CreateShareRequest();
        req.setSplitType("EQUAL");
        req.setInvitees(List.of(invitee("alice@example.com", null), invitee("bob@example.com", null)));

        service.createShares(100L, req);

        // total 8.40 / 3 people = 2.80
        ArgumentCaptor<ExpenseShare> captor = ArgumentCaptor.forClass(ExpenseShare.class);
        verify(shareRepo, times(2)).save(captor.capture());
        captor.getAllValues().forEach(s ->
                assertThat(s.getShareAmount()).isEqualByComparingTo("2.80"));
    }

    // ── CUSTOM split ──────────────────────────────────────────────────────────

    @Test
    void createCustomShares_usesSuppliedAmounts() {
        when(receiptRepo.findById(100L)).thenReturn(Optional.of(receipt));
        when(shareRepo.findByReceiptId(100L)).thenReturn(Collections.emptyList());

        ExpenseShare saved = mockSavedShare(1L, "alice@example.com", new BigDecimal("3.50"), "CUSTOM");
        when(shareRepo.save(any())).thenReturn(saved);

        CreateShareRequest req = new CreateShareRequest();
        req.setSplitType("CUSTOM");
        req.setInvitees(List.of(invitee("alice@example.com", new BigDecimal("3.50"))));

        service.createShares(100L, req);

        ArgumentCaptor<ExpenseShare> captor = ArgumentCaptor.forClass(ExpenseShare.class);
        verify(shareRepo).save(captor.capture());
        assertThat(captor.getValue().getShareAmount()).isEqualByComparingTo("3.50");
    }

    @Test
    void createCustomShares_zeroAmount_throws() {
        when(receiptRepo.findById(100L)).thenReturn(Optional.of(receipt));
        when(shareRepo.findByReceiptId(100L)).thenReturn(Collections.emptyList());

        CreateShareRequest req = new CreateShareRequest();
        req.setSplitType("CUSTOM");
        req.setInvitees(List.of(invitee("alice@example.com", BigDecimal.ZERO)));

        assertThatThrownBy(() -> service.createShares(100L, req))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("must be greater than zero");
    }

    // ── ITEM_BASED split ──────────────────────────────────────────────────────

    @Test
    void createItemBasedShares_taxableItem_addsTax() {
        when(receiptRepo.findById(100L)).thenReturn(Optional.of(receipt));
        when(shareRepo.findByReceiptId(100L)).thenReturn(Collections.emptyList());

        ExpenseShare saved = mockSavedShare(1L, "alice@example.com", new BigDecimal("5.25"), "ITEM_BASED");
        when(shareRepo.save(any())).thenReturn(saved);
        when(shareItemRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateShareRequest req = new CreateShareRequest();
        req.setSplitType("ITEM_BASED");
        req.setItemAssignments(List.of(assignment("alice@example.com", List.of(10L)))); // taxable item $5.00

        service.createShares(100L, req);

        ArgumentCaptor<ExpenseShare> captor = ArgumentCaptor.forClass(ExpenseShare.class);
        verify(shareRepo).save(captor.capture());
        // $5.00 item + $5.00 × 0.05 tax = $5.25
        assertThat(captor.getValue().getShareAmount()).isEqualByComparingTo("5.25");
        assertThat(captor.getValue().getSplitType()).isEqualTo("ITEM_BASED");
    }

    @Test
    void createItemBasedShares_nonTaxableItem_noTax() {
        when(receiptRepo.findById(100L)).thenReturn(Optional.of(receipt));
        when(shareRepo.findByReceiptId(100L)).thenReturn(Collections.emptyList());

        ExpenseShare saved = mockSavedShare(1L, "bob@example.com", new BigDecimal("3.00"), "ITEM_BASED");
        when(shareRepo.save(any())).thenReturn(saved);
        when(shareItemRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateShareRequest req = new CreateShareRequest();
        req.setSplitType("ITEM_BASED");
        req.setItemAssignments(List.of(assignment("bob@example.com", List.of(11L)))); // non-taxable $3.00

        service.createShares(100L, req);

        ArgumentCaptor<ExpenseShare> captor = ArgumentCaptor.forClass(ExpenseShare.class);
        verify(shareRepo).save(captor.capture());
        // $3.00 item, no tax
        assertThat(captor.getValue().getShareAmount()).isEqualByComparingTo("3.00");
    }

    @Test
    void createItemBasedShares_mixedItems_correctTotal() {
        when(receiptRepo.findById(100L)).thenReturn(Optional.of(receipt));
        when(shareRepo.findByReceiptId(100L)).thenReturn(Collections.emptyList());

        // taxable $5.00 + non-taxable $3.00 = $8.00 + 5% on $5.00 = $0.25 → $8.25
        ExpenseShare saved = mockSavedShare(1L, "carol@example.com", new BigDecimal("8.25"), "ITEM_BASED");
        when(shareRepo.save(any())).thenReturn(saved);
        when(shareItemRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateShareRequest req = new CreateShareRequest();
        req.setSplitType("ITEM_BASED");
        req.setItemAssignments(List.of(assignment("carol@example.com", List.of(10L, 11L))));

        service.createShares(100L, req);

        ArgumentCaptor<ExpenseShare> captor = ArgumentCaptor.forClass(ExpenseShare.class);
        verify(shareRepo).save(captor.capture());
        assertThat(captor.getValue().getShareAmount()).isEqualByComparingTo("8.25");
        // Two share-item rows saved
        verify(shareItemRepo, times(2)).save(any(ExpenseShareItem.class));
    }

    @Test
    void createItemBasedShares_itemNotOnReceipt_throws() {
        when(receiptRepo.findById(100L)).thenReturn(Optional.of(receipt));
        when(shareRepo.findByReceiptId(100L)).thenReturn(Collections.emptyList());

        CreateShareRequest req = new CreateShareRequest();
        req.setSplitType("ITEM_BASED");
        req.setItemAssignments(List.of(assignment("alice@example.com", List.of(999L)))); // unknown id

        assertThatThrownBy(() -> service.createShares(100L, req))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("does not belong to receipt");
    }

    @Test
    void createItemBasedShares_noAssignments_throws() {
        when(receiptRepo.findById(100L)).thenReturn(Optional.of(receipt));
        when(shareRepo.findByReceiptId(100L)).thenReturn(Collections.emptyList());

        CreateShareRequest req = new CreateShareRequest();
        req.setSplitType("ITEM_BASED");
        req.setItemAssignments(Collections.emptyList());

        assertThatThrownBy(() -> service.createShares(100L, req))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("itemAssignments required");
    }

    @Test
    void createShares_notOwner_throws() {
        User other = new User();
        other.setId(99L);
        other.setGoogleId("google-other");
        receipt.setUser(other); // receipt belongs to someone else

        when(receiptRepo.findById(100L)).thenReturn(Optional.of(receipt));

        CreateShareRequest req = new CreateShareRequest();
        req.setSplitType("EQUAL");
        req.setInvitees(List.of(invitee("alice@example.com", null)));

        assertThatThrownBy(() -> service.createShares(100L, req))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("do not own");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ShareInviteItem invitee(String email, BigDecimal amount) {
        ShareInviteItem i = new ShareInviteItem();
        i.setEmail(email);
        i.setAmount(amount);
        return i;
    }

    private ItemAssignment assignment(String email, List<Long> itemIds) {
        ItemAssignment a = new ItemAssignment();
        a.setEmail(email);
        a.setItemIds(itemIds);
        return a;
    }
}
