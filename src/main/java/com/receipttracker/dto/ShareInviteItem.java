package com.receipttracker.dto;

import java.math.BigDecimal;

public class ShareInviteItem {
    private String email;
    private BigDecimal amount;

    public ShareInviteItem() {}

    public ShareInviteItem(String email, BigDecimal amount) {
        this.email = email;
        this.amount = amount;
    }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
}
