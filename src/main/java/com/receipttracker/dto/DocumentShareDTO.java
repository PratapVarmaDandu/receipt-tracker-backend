package com.receipttracker.dto;

import java.time.LocalDateTime;
import java.util.List;

public class DocumentShareDTO {
    private Long id;
    private String recipientEmail;
    private String recipientName;
    private String purpose;
    private String message;
    private String shareToken;
    private LocalDateTime expiresAt;
    private LocalDateTime sharedAt;
    private boolean accessed;
    private boolean expired;
    private List<DocumentDTO> documents;

    public DocumentShareDTO() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRecipientEmail() { return recipientEmail; }
    public void setRecipientEmail(String recipientEmail) { this.recipientEmail = recipientEmail; }
    public String getRecipientName() { return recipientName; }
    public void setRecipientName(String recipientName) { this.recipientName = recipientName; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getShareToken() { return shareToken; }
    public void setShareToken(String shareToken) { this.shareToken = shareToken; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getSharedAt() { return sharedAt; }
    public void setSharedAt(LocalDateTime sharedAt) { this.sharedAt = sharedAt; }
    public boolean isAccessed() { return accessed; }
    public void setAccessed(boolean accessed) { this.accessed = accessed; }
    public boolean isExpired() { return expired; }
    public void setExpired(boolean expired) { this.expired = expired; }
    public List<DocumentDTO> getDocuments() { return documents; }
    public void setDocuments(List<DocumentDTO> documents) { this.documents = documents; }
}
