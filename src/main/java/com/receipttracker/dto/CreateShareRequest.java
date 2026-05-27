package com.receipttracker.dto;

import java.util.List;

public class CreateShareRequest {
    private String splitType;
    private List<ShareInviteItem> invitees;

    public CreateShareRequest() {}

    public String getSplitType() { return splitType; }
    public void setSplitType(String splitType) { this.splitType = splitType; }
    public List<ShareInviteItem> getInvitees() { return invitees; }
    public void setInvitees(List<ShareInviteItem> invitees) { this.invitees = invitees; }
}
