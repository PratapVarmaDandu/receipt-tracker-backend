package com.receipttracker.dto;

public class OwnerActionRequest {
    private String action;
    private String responseNote;

    public OwnerActionRequest() {}

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getResponseNote() { return responseNote; }
    public void setResponseNote(String responseNote) { this.responseNote = responseNote; }
}
