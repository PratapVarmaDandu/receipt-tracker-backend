package com.receipttracker.dto;

import com.receipttracker.model.SubmissionStatus;
import com.receipttracker.model.SubmissionType;

import java.time.LocalDateTime;

/** Admin-facing view of a UserSubmission — includes submitter identity, unlike UserSubmissionDTO. */
public class PlatformSubmissionDTO {
    private Long id;
    private SubmissionType type;
    private String message;
    private String attachmentMimeType;
    private SubmissionStatus status;
    private boolean rewardGranted;
    private LocalDateTime createdAt;
    private String submitterName;
    private String submitterEmail;

    public PlatformSubmissionDTO() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public SubmissionType getType() { return type; }
    public void setType(SubmissionType type) { this.type = type; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getAttachmentMimeType() { return attachmentMimeType; }
    public void setAttachmentMimeType(String attachmentMimeType) { this.attachmentMimeType = attachmentMimeType; }

    public SubmissionStatus getStatus() { return status; }
    public void setStatus(SubmissionStatus status) { this.status = status; }

    public boolean isRewardGranted() { return rewardGranted; }
    public void setRewardGranted(boolean rewardGranted) { this.rewardGranted = rewardGranted; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getSubmitterName() { return submitterName; }
    public void setSubmitterName(String submitterName) { this.submitterName = submitterName; }

    public String getSubmitterEmail() { return submitterEmail; }
    public void setSubmitterEmail(String submitterEmail) { this.submitterEmail = submitterEmail; }
}
