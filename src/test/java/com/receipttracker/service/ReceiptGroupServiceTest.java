package com.receipttracker.service;

import com.receipttracker.dto.ReceiptDTO;
import com.receipttracker.model.*;
import com.receipttracker.repository.ExpenseGroupRepository;
import com.receipttracker.repository.GroupMemberRepository;
import com.receipttracker.repository.ReceiptRepository;
import com.receipttracker.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReceiptGroupServiceTest {

    @Mock private ReceiptRepository receiptRepo;
    @Mock private UserRepository userRepository;
    @Mock private ExpenseGroupRepository groupRepo;
    @Mock private GroupMemberRepository memberRepo;
    @Mock private OcrService ocrService;
    @Mock private ReceiptParserService parserService;
    @Mock private CashbackService cashbackService;
    @Mock private ClaudeVisionService claudeVisionService;
    @Mock private UserStorageService userStorageService;
    @Mock private com.receipttracker.config.StoragePathResolver storagePathResolver;

    @InjectMocks
    private ReceiptService service;

    private User owner;
    private Receipt receipt;
    private ExpenseGroup group;

    @BeforeEach
    void setUp() {
        owner = new User();
        owner.setId(1L);
        owner.setGoogleId("google-owner");
        owner.setEmail("owner@example.com");
        owner.setName("Owner");

        receipt = new Receipt();
        receipt.setId(100L);
        receipt.setUser(owner);
        receipt.setStoreName("Whole Foods");
        receipt.setTotal(new BigDecimal("42.00"));
        receipt.setItems(new ArrayList<>());

        group = new ExpenseGroup();
        group.setId(5L);
        group.setName("Road Trip");
        group.setOwner(owner);

        mockSecurityContext("google-owner");
        when(userRepository.findByGoogleId("google-owner")).thenReturn(Optional.of(owner));
        // Cashback stubs so toDTO() doesn't NPE
        when(cashbackService.calculateCashbackForReceipt(any())).thenReturn(BigDecimal.ZERO);
        when(cashbackService.calculateBestPossibleCashback(any())).thenReturn(BigDecimal.ZERO);
        when(cashbackService.bestCardDisplay(any())).thenReturn("");
        when(cashbackService.bestCardRate(any())).thenReturn("");
    }

    private void mockSecurityContext(String googleId) {
        OAuth2User principal = mock(OAuth2User.class);
        when(principal.getAttribute("sub")).thenReturn(googleId);
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(principal);
        SecurityContext ctx = mock(SecurityContext.class);
        when(ctx.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(ctx);
    }

    @Test
    void addToGroup_success_setsGroup() {
        when(receiptRepo.findById(100L)).thenReturn(Optional.of(receipt));
        when(groupRepo.findById(5L)).thenReturn(Optional.of(group));
        when(memberRepo.existsByGroupAndUser(group, owner)).thenReturn(true);
        when(receiptRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReceiptDTO result = service.addToGroup(100L, 5L);

        assertThat(result.getGroupId()).isEqualTo(5L);
        assertThat(result.getGroupName()).isEqualTo("Road Trip");
        assertThat(receipt.getGroup()).isEqualTo(group);
    }

    @Test
    void addToGroup_nullGroupId_unassigns() {
        receipt.setGroup(group);
        when(receiptRepo.findById(100L)).thenReturn(Optional.of(receipt));
        when(receiptRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReceiptDTO result = service.addToGroup(100L, null);

        assertThat(result.getGroupId()).isNull();
        assertThat(receipt.getGroup()).isNull();
    }

    @Test
    void addToGroup_notOwner_throws() {
        User stranger = new User();
        stranger.setId(99L);
        receipt.setUser(stranger);

        when(receiptRepo.findById(100L)).thenReturn(Optional.of(receipt));

        assertThatThrownBy(() -> service.addToGroup(100L, 5L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("do not own");
    }

    @Test
    void addToGroup_notGroupMember_throws() {
        when(receiptRepo.findById(100L)).thenReturn(Optional.of(receipt));
        when(groupRepo.findById(5L)).thenReturn(Optional.of(group));
        when(memberRepo.existsByGroupAndUser(group, owner)).thenReturn(false);

        assertThatThrownBy(() -> service.addToGroup(100L, 5L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not a member");
    }

    @Test
    void addToGroup_groupNotFound_throws() {
        when(receiptRepo.findById(100L)).thenReturn(Optional.of(receipt));
        when(groupRepo.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addToGroup(100L, 999L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Group not found");
    }
}
