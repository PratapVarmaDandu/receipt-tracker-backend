package com.receipttracker.dto;

import java.math.BigDecimal;

public class InviteeActionRequest {
    private String action;
    private BigDecimal counterAmount;
    private String counterNote;

    public InviteeActionRequest() {}

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public BigDecimal getCounterAmount() { return counterAmount; }
    public void setCounterAmount(BigDecimal counterAmount) { this.counterAmount = counterAmount; }
    public String getCounterNote() { return counterNote; }
    public void setCounterNote(String counterNote) { this.counterNote = counterNote; }
}
