package com.receipttracker.dto;

public class RegisterDeviceTokenRequest {
    private String token;
    private String platform; // "IOS" | "ANDROID"

    public RegisterDeviceTokenRequest() {}

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
}
