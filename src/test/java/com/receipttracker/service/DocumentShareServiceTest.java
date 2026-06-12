package com.receipttracker.service;

import com.receipttracker.dto.CreateDocumentShareRequest;
import com.receipttracker.dto.DocumentShareDTO;
import com.receipttracker.model.*;
import com.receipttracker.repository.DocumentRepository;
import com.receipttracker.repository.DocumentShareRepository;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentShareServiceTest {

    @Mock private DocumentShareRepository shareRepo;
    @Mock private DocumentRepository documentRepo;
    @Mock private UserRepository userRepo;
    @Mock private DocumentService documentService;
    @Mock private EmailService emailService;
    @Mock private FeatureEntitlementService entitlement;

    @InjectMocks private DocumentShareService service;

    private User owner;
    private Document doc;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "frontendUrl", "http://localhost:4200");

        owner = new User();
        owner.setId(1L);
        owner.setGoogleId("g-owner");
        owner.setEmail("owner@example.com");
        owner.setName("Owner Name");

        doc = new Document();
        doc.setId(10L);
        doc.setTitle("W2 2024");
        doc.setOriginalFileName("w2.pdf");
        doc.setStoredFileName("uuid-w2.pdf");
        doc.setContentType("application/pdf");
        doc.setCategory(DocumentCategory.TAX);
        doc.setUser(owner);
        doc.setNextSteps(new ArrayList<>());

        mockSecurityContext("g-owner");
        when(userRepo.findByGoogleId("g-owner")).thenReturn(Optional.of(owner));
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

    // ── Create share ─────────────────────────────────────────────────────────

    @Test
    void createShare_success_sendsEmail() {
        when(documentRepo.findById(10L)).thenReturn(Optional.of(doc));

        DocumentShare saved = new DocumentShare();
        saved.setId(1L);
        saved.setOwner(owner);
        saved.setRecipientEmail("attorney@example.com");
        saved.setRecipientName("Tax Attorney");
        saved.setPurpose("Tax Filing 2024");
        saved.setShareToken("abc123token");
        saved.setExpiresAt(LocalDateTime.now().plusDays(7));
        saved.setDocuments(List.of(doc));
        when(shareRepo.save(any())).thenReturn(saved);
        when(documentService.toDTO(any())).thenCallRealMethod();

        CreateDocumentShareRequest req = new CreateDocumentShareRequest();
        req.setDocumentIds(List.of(10L));
        req.setRecipientEmail("attorney@example.com");
        req.setRecipientName("Tax Attorney");
        req.setPurpose("Tax Filing 2024");
        req.setExpiryDays(7);

        service.createShare(req);

        verify(shareRepo).save(any(DocumentShare.class));
        verify(emailService).sendDocumentShare(
                eq("attorney@example.com"),
                eq("Tax Attorney"),
                eq("Owner Name"),
                eq("Tax Filing 2024"),
                isNull(),
                eq(1),
                contains("abc123token"),
                eq(7)
        );
    }

    @Test
    void createShare_invalidEmail_throws() {
        CreateDocumentShareRequest req = new CreateDocumentShareRequest();
        req.setDocumentIds(List.of(10L));
        req.setRecipientEmail("not-an-email");

        assertThatThrownBy(() -> service.createShare(req))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Invalid recipient email");
    }

    @Test
    void createShare_noDocuments_throws() {
        CreateDocumentShareRequest req = new CreateDocumentShareRequest();
        req.setRecipientEmail("a@b.com");
        req.setDocumentIds(List.of());

        assertThatThrownBy(() -> service.createShare(req))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("At least one document");
    }

    @Test
    void createShare_notOwnerOfDoc_throws() {
        User stranger = new User();
        stranger.setId(99L);
        doc.setUser(stranger);

        when(documentRepo.findById(10L)).thenReturn(Optional.of(doc));

        CreateDocumentShareRequest req = new CreateDocumentShareRequest();
        req.setDocumentIds(List.of(10L));
        req.setRecipientEmail("a@b.com");

        assertThatThrownBy(() -> service.createShare(req))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Access denied");
    }

    @Test
    void createShare_expiryDaysCappedAt30() {
        when(documentRepo.findById(10L)).thenReturn(Optional.of(doc));

        DocumentShare saved = new DocumentShare();
        saved.setId(1L);
        saved.setOwner(owner);
        saved.setRecipientEmail("a@b.com");
        saved.setShareToken("tok");
        saved.setDocuments(List.of(doc));
        when(shareRepo.save(any())).thenReturn(saved);

        CreateDocumentShareRequest req = new CreateDocumentShareRequest();
        req.setDocumentIds(List.of(10L));
        req.setRecipientEmail("a@b.com");
        req.setExpiryDays(999); // should be capped at 30

        service.createShare(req);

        // Verify email is called with capped value
        verify(emailService).sendDocumentShare(any(), any(), any(), any(), any(),
                anyInt(), any(), eq(30));
    }

    // ── Token access ─────────────────────────────────────────────────────────

    @Test
    void getByToken_expiredLink_throws() {
        DocumentShare share = new DocumentShare();
        share.setShareToken("expired-tok");
        share.setExpiresAt(LocalDateTime.now().minusHours(1));
        share.setDocuments(new ArrayList<>());

        when(shareRepo.findByShareToken("expired-tok")).thenReturn(Optional.of(share));

        assertThatThrownBy(() -> service.getByToken("expired-tok"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void getByToken_validLink_marksAccessed() {
        DocumentShare share = new DocumentShare();
        share.setId(1L);
        share.setShareToken("valid-tok");
        share.setOwner(owner);
        share.setRecipientEmail("a@b.com");
        share.setExpiresAt(LocalDateTime.now().plusDays(5));
        share.setAccessed(false);
        share.setDocuments(new ArrayList<>());

        when(shareRepo.findByShareToken("valid-tok")).thenReturn(Optional.of(share));
        when(shareRepo.save(any())).thenReturn(share);

        service.getByToken("valid-tok");

        assertThat(share.isAccessed()).isTrue();
        assertThat(share.getAccessedAt()).isNotNull();
    }

    @Test
    void downloadViaToken_docNotInShare_throws() {
        DocumentShare share = new DocumentShare();
        share.setShareToken("tok");
        share.setExpiresAt(LocalDateTime.now().plusDays(5));
        share.setDocuments(List.of(doc)); // only doc 10

        when(shareRepo.findByShareToken("tok")).thenReturn(Optional.of(share));

        // Try to download doc 99 which is not in the share
        assertThatThrownBy(() -> service.downloadViaToken("tok", 99L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not in this share");
    }
}
