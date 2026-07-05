package com.receipttracker.service;

import com.receipttracker.config.StoragePathResolver;
import com.receipttracker.dto.PlatformSubmissionDTO;
import com.receipttracker.model.SubmissionStatus;
import com.receipttracker.model.SubmissionType;
import com.receipttracker.model.User;
import com.receipttracker.model.UserSubmission;
import com.receipttracker.repository.UserRepository;
import com.receipttracker.repository.UserSubmissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserSubmissionServiceTest {

    @Mock private UserSubmissionRepository submissionRepo;
    @Mock private UserRepository userRepo;
    @Mock private StoragePathResolver storagePathResolver;
    @Mock private EmailService emailService;

    @InjectMocks private UserSubmissionService service;

    @TempDir Path tempDir;

    private User submitter;
    private UserSubmission submission;

    @BeforeEach
    void setUp() {
        submitter = new User();
        submitter.setId(7L);
        submitter.setName("Jane Doe");
        submitter.setEmail("jane@example.com");

        submission = new UserSubmission();
        submission.setId(1L);
        submission.setUser(submitter);
        submission.setType(SubmissionType.BUG_REPORT);
        submission.setMessage("Broken button");
        submission.setStatus(SubmissionStatus.NEW);

        when(submissionRepo.findById(1L)).thenReturn(Optional.of(submission));
        when(storagePathResolver.asPath()).thenReturn(tempDir);
    }

    @Test
    void listForPlatformReturnsSubmitterIdentity() {
        when(submissionRepo.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(submission));

        List<PlatformSubmissionDTO> result = service.listForPlatform(null, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSubmitterName()).isEqualTo("Jane Doe");
        assertThat(result.get(0).getSubmitterEmail()).isEqualTo("jane@example.com");
    }

    @Test
    void listForPlatformFiltersByType() {
        when(submissionRepo.findByTypeOrderByCreatedAtDesc(SubmissionType.BUG_REPORT))
                .thenReturn(List.of(submission));

        List<PlatformSubmissionDTO> result = service.listForPlatform("bug_report", null);

        assertThat(result).hasSize(1);
    }

    @Test
    void listForPlatformFiltersByStatusInMemory() {
        UserSubmission resolved = new UserSubmission();
        resolved.setId(2L);
        resolved.setUser(submitter);
        resolved.setStatus(SubmissionStatus.RESOLVED);
        when(submissionRepo.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(submission, resolved));

        List<PlatformSubmissionDTO> result = service.listForPlatform(null, "resolved");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(2L);
    }

    @Test
    void updateStatusPersistsNewStatus() {
        when(submissionRepo.save(submission)).thenReturn(submission);

        PlatformSubmissionDTO result = service.updateStatus(1L, "REVIEWED");

        assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.REVIEWED);
        assertThat(result.getStatus()).isEqualTo(SubmissionStatus.REVIEWED);
    }

    @Test
    void updateStatusRejectsInvalidValue() {
        assertThatThrownBy(() -> service.updateStatus(1L, "NOT_A_STATUS"))
                .hasMessageContaining("Invalid status");
    }

    @Test
    void streamAttachmentReturnsFileWhenPresent() throws IOException {
        submission.setAttachmentPath("uuid.png");
        submission.setAttachmentMimeType("image/png");
        Path userDir = tempDir.resolve("submissions").resolve("7");
        Files.createDirectories(userDir);
        Files.writeString(userDir.resolve("uuid.png"), "fake-image-bytes");

        Resource resource = service.streamAttachment(1L);

        assertThat(resource.exists()).isTrue();
    }

    @Test
    void streamAttachmentRejectsWhenNoAttachment() {
        assertThatThrownBy(() -> service.streamAttachment(1L))
                .hasMessageContaining("no attachment");
    }

    @Test
    void streamAttachmentRejectsWhenFileMissingFromDisk() {
        submission.setAttachmentPath("missing.png");
        submission.setAttachmentMimeType("image/png");

        assertThatThrownBy(() -> service.streamAttachment(1L))
                .hasMessageContaining("not found in storage");
    }

    @Test
    void unknownSubmissionIdRejectedEverywhere() {
        when(submissionRepo.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateStatus(99L, "REVIEWED"))
                .hasMessageContaining("Submission not found");
        assertThatThrownBy(() -> service.streamAttachment(99L))
                .hasMessageContaining("Submission not found");
    }
}
