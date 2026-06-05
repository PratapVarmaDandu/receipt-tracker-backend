package com.receipttracker.dto;

import java.util.List;

public class ItemAssignment {
    private String email;
    private List<Long> itemIds;

    public ItemAssignment() {}

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public List<Long> getItemIds() { return itemIds; }
    public void setItemIds(List<Long> itemIds) { this.itemIds = itemIds; }
}
