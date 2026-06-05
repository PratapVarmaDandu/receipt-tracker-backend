package com.receipttracker.dto;

import com.receipttracker.model.ShareStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class ExpenseShareDTO {
    private Long id;
    private Long receiptId;
    private String storeName;
    private String inviteeEmail;
    private boolean inviteeLinked;
    private BigDecimal shareAmount;
    private BigDecimal counterAmount;
    private String shareNote;
    private String counterNote;
    private String changeResponseNote;
    private ShareStatus status;
    private String inviteToken;
    private String splitType;
    private List<ExpenseShareItemDTO> items;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public ExpenseShareDTO() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getReceiptId() { return receiptId; }
    public void setReceiptId(Long receiptId) { this.receiptId = receiptId; }
    public String getStoreName() { return storeName; }
    public void setStoreName(String storeName) { this.storeName = storeName; }
    public String getInviteeEmail() { return inviteeEmail; }
    public void setInviteeEmail(String inviteeEmail) { this.inviteeEmail = inviteeEmail; }
    public boolean isInviteeLinked() { return inviteeLinked; }
    public void setInviteeLinked(boolean inviteeLinked) { this.inviteeLinked = inviteeLinked; }
    public BigDecimal getShareAmount() { return shareAmount; }
    public void setShareAmount(BigDecimal shareAmount) { this.shareAmount = shareAmount; }
    public BigDecimal getCounterAmount() { return counterAmount; }
    public void setCounterAmount(BigDecimal counterAmount) { this.counterAmount = counterAmount; }
    public String getShareNote() { return shareNote; }
    public void setShareNote(String shareNote) { this.shareNote = shareNote; }
    public String getCounterNote() { return counterNote; }
    public void setCounterNote(String counterNote) { this.counterNote = counterNote; }
    public String getChangeResponseNote() { return changeResponseNote; }
    public void setChangeResponseNote(String changeResponseNote) { this.changeResponseNote = changeResponseNote; }
    public ShareStatus getStatus() { return status; }
    public void setStatus(ShareStatus status) { this.status = status; }
    public String getInviteToken() { return inviteToken; }
    public void setInviteToken(String inviteToken) { this.inviteToken = inviteToken; }
    public String getSplitType() { return splitType; }
    public void setSplitType(String splitType) { this.splitType = splitType; }
    public List<ExpenseShareItemDTO> getItems() { return items; }
    public void setItems(List<ExpenseShareItemDTO> items) { this.items = items; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
