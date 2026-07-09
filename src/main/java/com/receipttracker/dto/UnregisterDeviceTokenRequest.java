package com.receipttracker.dto;

public class UnregisterDeviceTokenRequest {
    private String token;

    public UnregisterDeviceTokenRequest() {}

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
}
