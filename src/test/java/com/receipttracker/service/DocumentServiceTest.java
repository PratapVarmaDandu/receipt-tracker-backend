package com.receipttracker.service;

import com.receipttracker.config.StoragePathResolver;
import com.receipttracker.dto.DocumentDTO;
import com.receipttracker.dto.DocumentNextStepDTO;
import com.receipttracker.model.*;
import com.receipttracker.repository.DocumentNextStepRepository;
import com.receipttracker.repository.DocumentRepository;
import com.receipttracker.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock private DocumentRepository documentRepo;
    @Mock private DocumentNextStepRepository nextStepRepo;
    @Mock private UserRepository userRepo;
    @Mock private StoragePathResolver storagePathResolver;

    @InjectMocks private DocumentService service;

    @TempDir Path tempDir;

    private User owner;

    @BeforeEach
    void setUp() {
        owner = new User();
        owner.setId(1L);
        owner.setGoogleId("g-owner");
        owner.setEmail("owner@example.com");

        mockSecurityContext("g-owner");
        when(userRepo.findByGoogleId("g-owner")).thenReturn(Optional.of(owner));
        when(storagePathResolver.asPath()).thenReturn(tempDir);
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

    private Document makeDoc(Long id, DocumentCategory cat, LocalDate expiry) {
        Document d = new Document();
        d.setId(id);
        d.setTitle("Test Doc");
        d.setOriginalFileName("test.pdf");
        d.setStoredFileName("uuid-test.pdf");
        d.setContentType("application/pdf");
        d.setFileSize(1024L);
        d.setCategory(cat);
        d.setUser(owner);
        d.setExpiryDate(expiry);
        d.setNextSteps(new ArrayList<>());
        return d;
    }

    // ── Upload ───────────────────────────────────────────────────────────────

    @Test
    void upload_validPdf_savesDocument() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file", "resume.pdf", "application/pdf", "PDF content".getBytes());

        Document saved = makeDoc(1L, DocumentCategory.RESUME, null);
        when(documentRepo.save(any())).thenReturn(saved);

        DocumentDTO result = service.upload(file, "My Resume", "RESUME",
                null, null, null, null);

        assertThat(result.getCategory()).isEqualTo(DocumentCategory.RESUME);
        verify(documentRepo).save(any(Document.class));
    }

    @Test
    void upload_blockedMimeType_throws() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "malware.exe", "application/x-executable", new byte[]{});

        assertThatThrownBy(() -> service.upload(file, "Bad", "RESUME",
                null, null, null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("File type not allowed");
    }

    @Test
    void upload_blockedExtension_throws() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "script.sh", "text/plain", "#!/bin/bash".getBytes());

        assertThatThrownBy(() -> service.upload(file, "Script", "OTHER",
                null, null, null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("File extension not allowed");
    }

    // ── Status computation ────────────────────────────────────────────────────

    @Test
    void toDTO_expiredDocument_statusIsExpired() {
        Document d = makeDoc(1L, DocumentCategory.IMMIGRATION, LocalDate.now().minusDays(1));
        DocumentDTO dto = service.toDTO(d);
        assertThat(dto.getStatus()).isEqualTo("EXPIRED");
        assertThat(dto.getDaysUntilExpiry()).isNegative();
    }

    @Test
    void toDTO_expiringSoonDocument_statusIsExpiringSoon() {
        Document d = makeDoc(1L, DocumentCategory.IMMIGRATION, LocalDate.now().plusDays(30));
        DocumentDTO dto = service.toDTO(d);
        assertThat(dto.getStatus()).isEqualTo("EXPIRING_SOON");
        assertThat(dto.getDaysUntilExpiry()).isBetween(0, 90);
    }

    @Test
    void toDTO_activeDocument_statusIsActive() {
        Document d = makeDoc(1L, DocumentCategory.TAX, LocalDate.now().plusYears(2));
        DocumentDTO dto = service.toDTO(d);
        assertThat(dto.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void toDTO_noExpiry_statusIsActive() {
        Document d = makeDoc(1L, DocumentCategory.RESUME, null);
        DocumentDTO dto = service.toDTO(d);
        assertThat(dto.getStatus()).isEqualTo("ACTIVE");
        assertThat(dto.getDaysUntilExpiry()).isNull();
    }

    // ── Access control ────────────────────────────────────────────────────────

    @Test
    void getById_notOwner_throws() {
        User stranger = new User();
        stranger.setId(99L);
        Document d = makeDoc(1L, DocumentCategory.INCOME, null);
        d.setUser(stranger);

        when(documentRepo.findById(1L)).thenReturn(Optional.of(d));

        assertThatThrownBy(() -> service.getById(1L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Access denied");
    }

    @Test
    void archive_ownerSuccess() {
        Document d = makeDoc(5L, DocumentCategory.TAX, null);
        when(documentRepo.findById(5L)).thenReturn(Optional.of(d));
        when(documentRepo.save(any())).thenReturn(d);

        service.archive(5L);

        assertThat(d.isArchived()).isTrue();
    }

    // ── Next steps ────────────────────────────────────────────────────────────

    @Test
    void addNextStep_success() {
        Document d = makeDoc(1L, DocumentCategory.IMMIGRATION, null);
        when(documentRepo.findById(1L)).thenReturn(Optional.of(d));

        DocumentNextStep step = new DocumentNextStep();
        step.setId(10L);
        step.setDocument(d);
        step.setTitle("File I-485");
        when(nextStepRepo.save(any())).thenReturn(step);

        DocumentNextStepDTO dto = service.addNextStep(1L, "File I-485", "Submit to USCIS", null);
        assertThat(dto.getTitle()).isEqualTo("File I-485");
    }

    @Test
    void completeNextStep_setsCompletedAndTimestamp() {
        Document d = makeDoc(1L, DocumentCategory.IMMIGRATION, null);
        DocumentNextStep step = new DocumentNextStep();
        step.setId(10L);
        step.setDocument(d);
        step.setTitle("File I-485");
        step.setCompleted(false);

        when(nextStepRepo.findById(10L)).thenReturn(Optional.of(step));
        when(nextStepRepo.save(any())).thenReturn(step);

        DocumentNextStepDTO dto = service.completeNextStep(10L);

        assertThat(step.isCompleted()).isTrue();
        assertThat(step.getCompletedAt()).isNotNull();
    }

    // ── Summary ───────────────────────────────────────────────────────────────

    @Test
    void getSummary_returnsCorrectCounts() {
        Document activeDoc = makeDoc(1L, DocumentCategory.TAX, LocalDate.now().plusYears(1));
        Document expiringDoc = makeDoc(2L, DocumentCategory.IMMIGRATION, LocalDate.now().plusDays(30));
        Document expiredDoc = makeDoc(3L, DocumentCategory.IMMIGRATION, LocalDate.now().minusDays(5));

        when(documentRepo.findAllActiveByUser(owner)).thenReturn(List.of(activeDoc, expiringDoc, expiredDoc));
        when(nextStepRepo.findPendingByUser(owner)).thenReturn(List.of());

        Map<String, Object> summary = service.getSummary();

        assertThat(summary.get("total")).isEqualTo(3);
        assertThat(summary.get("expiringSoon")).isEqualTo(1L);
        assertThat(summary.get("expired")).isEqualTo(1L);
    }
}
