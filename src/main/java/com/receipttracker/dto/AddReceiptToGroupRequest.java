package com.receipttracker.dto;

public class AddReceiptToGroupRequest {
    // null = unassign from group
    private Long groupId;

    public AddReceiptToGroupRequest() {}

    public Long getGroupId() { return groupId; }
    public void setGroupId(Long groupId) { this.groupId = groupId; }
}
