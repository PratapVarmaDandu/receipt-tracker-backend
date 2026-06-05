package com.receipttracker.dto;

import java.util.List;

public class CreateDocumentShareRequest {
    private List<Long> documentIds;
    private String recipientEmail;
    private String recipientName;
    private String purpose;
    private String message;
    /** Link validity in days (default 7, max 30). */
    private int expiryDays = 7;

    public CreateDocumentShareRequest() {}

    public List<Long> getDocumentIds() { return documentIds; }
    public void setDocumentIds(List<Long> documentIds) { this.documentIds = documentIds; }
    public String getRecipientEmail() { return recipientEmail; }
    public void setRecipientEmail(String recipientEmail) { this.recipientEmail = recipientEmail; }
    public String getRecipientName() { return recipientName; }
    public void setRecipientName(String recipientName) { this.recipientName = recipientName; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public int getExpiryDays() { return expiryDays; }
    public void setExpiryDays(int expiryDays) { this.expiryDays = expiryDays; }
}
