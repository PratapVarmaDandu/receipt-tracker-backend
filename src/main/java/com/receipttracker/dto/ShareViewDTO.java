package com.receipttracker.dto;

import com.receipttracker.model.ShareStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class ShareViewDTO {
    private String storeName;
    private String ownerName;
    private BigDecimal receiptTotal;
    private BigDecimal shareAmount;
    private BigDecimal counterAmount;
    private LocalDateTime purchaseDateTime;
    private ShareStatus status;
    private String shareNote;
    private String counterNote;
    private String changeResponseNote;
    private String splitType;
    private List<ReceiptItemDTO> items;
    private boolean inviteeLinkNeeded;
    // populated for ITEM_BASED shares only
    private List<ExpenseShareItemDTO> assignedItems;
    private BigDecimal itemSubtotal;
    private BigDecimal itemTax;

    public ShareViewDTO() {}

    public String getStoreName() { return storeName; }
    public void setStoreName(String storeName) { this.storeName = storeName; }
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
    public BigDecimal getReceiptTotal() { return receiptTotal; }
    public void setReceiptTotal(BigDecimal receiptTotal) { this.receiptTotal = receiptTotal; }
    public BigDecimal getShareAmount() { return shareAmount; }
    public void setShareAmount(BigDecimal shareAmount) { this.shareAmount = shareAmount; }
    public BigDecimal getCounterAmount() { return counterAmount; }
    public void setCounterAmount(BigDecimal counterAmount) { this.counterAmount = counterAmount; }
    public LocalDateTime getPurchaseDateTime() { return purchaseDateTime; }
    public void setPurchaseDateTime(LocalDateTime purchaseDateTime) { this.purchaseDateTime = purchaseDateTime; }
    public ShareStatus getStatus() { return status; }
    public void setStatus(ShareStatus status) { this.status = status; }
    public String getShareNote() { return shareNote; }
    public void setShareNote(String shareNote) { this.shareNote = shareNote; }
    public String getCounterNote() { return counterNote; }
    public void setCounterNote(String counterNote) { this.counterNote = counterNote; }
    public String getChangeResponseNote() { return changeResponseNote; }
    public void setChangeResponseNote(String changeResponseNote) { this.changeResponseNote = changeResponseNote; }
    public List<ReceiptItemDTO> getItems() { return items; }
    public void setItems(List<ReceiptItemDTO> items) { this.items = items; }
    public boolean isInviteeLinkNeeded() { return inviteeLinkNeeded; }
    public void setInviteeLinkNeeded(boolean inviteeLinkNeeded) { this.inviteeLinkNeeded = inviteeLinkNeeded; }
    public String getSplitType() { return splitType; }
    public void setSplitType(String splitType) { this.splitType = splitType; }
    public List<ExpenseShareItemDTO> getAssignedItems() { return assignedItems; }
    public void setAssignedItems(List<ExpenseShareItemDTO> assignedItems) { this.assignedItems = assignedItems; }
    public BigDecimal getItemSubtotal() { return itemSubtotal; }
    public void setItemSubtotal(BigDecimal itemSubtotal) { this.itemSubtotal = itemSubtotal; }
    public BigDecimal getItemTax() { return itemTax; }
    public void setItemTax(BigDecimal itemTax) { this.itemTax = itemTax; }
}
