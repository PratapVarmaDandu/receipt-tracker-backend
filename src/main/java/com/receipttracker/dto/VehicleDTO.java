package com.receipttracker.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public class VehicleDTO {
    // ── Sharing metadata (populated by VehicleService) ────────────────────────
    private boolean isShared;           // true when this vehicle was shared with the current user
    private String ownerName;           // only set when isShared=true
    private List<VehicleAccessDTO> sharedWith; // only populated for the owner
    private Long id;
    private String make;
    private String model;
    private Integer year;
    private String trim;
    private String vin;
    private String color;
    private String licensePlate;
    private String registrationState;
    private LocalDate tagExpirationDate;
    private Integer daysUntilTagExpiry;
    private String tagStatus;              // VALID | EXPIRING_SOON | EXPIRED
    private String insuranceProvider;
    private String insurancePolicyNumber;
    private LocalDate insuranceExpiryDate;
    private String insuranceStatus;        // VALID | EXPIRING_SOON | EXPIRED
    private LocalDate purchaseDate;
    private BigDecimal purchasePrice;
    private boolean purchasedFromDealer;
    private String dealerName;
    private Integer currentMileage;
    private String notes;
    private List<String> photoFilenames;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ── Computed stats (populated by VehicleService) ──────────────────────────
    private BigDecimal totalMaintenanceCost;
    private Double averageMpg;
    private Integer maintenanceCount;
    private String nextServiceDue;     // human-readable: e.g. "Oil Change due in 800 miles"
    private boolean hasOpenRecalls;

    public VehicleDTO() {}

    // ── Getters / Setters ─────────────────────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getMake() { return make; }
    public void setMake(String make) { this.make = make; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Integer getModelYear() { return year; }
    public void setModelYear(Integer year) { this.year = year; }
    public String getTrim() { return trim; }
    public void setTrim(String trim) { this.trim = trim; }
    public String getVin() { return vin; }
    public void setVin(String vin) { this.vin = vin; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public String getLicensePlate() { return licensePlate; }
    public void setLicensePlate(String licensePlate) { this.licensePlate = licensePlate; }
    public String getRegistrationState() { return registrationState; }
    public void setRegistrationState(String registrationState) { this.registrationState = registrationState; }
    public LocalDate getTagExpirationDate() { return tagExpirationDate; }
    public void setTagExpirationDate(LocalDate tagExpirationDate) { this.tagExpirationDate = tagExpirationDate; }
    public Integer getDaysUntilTagExpiry() { return daysUntilTagExpiry; }
    public void setDaysUntilTagExpiry(Integer daysUntilTagExpiry) { this.daysUntilTagExpiry = daysUntilTagExpiry; }
    public String getTagStatus() { return tagStatus; }
    public void setTagStatus(String tagStatus) { this.tagStatus = tagStatus; }
    public String getInsuranceProvider() { return insuranceProvider; }
    public void setInsuranceProvider(String insuranceProvider) { this.insuranceProvider = insuranceProvider; }
    public String getInsurancePolicyNumber() { return insurancePolicyNumber; }
    public void setInsurancePolicyNumber(String insurancePolicyNumber) { this.insurancePolicyNumber = insurancePolicyNumber; }
    public LocalDate getInsuranceExpiryDate() { return insuranceExpiryDate; }
    public void setInsuranceExpiryDate(LocalDate insuranceExpiryDate) { this.insuranceExpiryDate = insuranceExpiryDate; }
    public String getInsuranceStatus() { return insuranceStatus; }
    public void setInsuranceStatus(String insuranceStatus) { this.insuranceStatus = insuranceStatus; }
    public LocalDate getPurchaseDate() { return purchaseDate; }
    public void setPurchaseDate(LocalDate purchaseDate) { this.purchaseDate = purchaseDate; }
    public BigDecimal getPurchasePrice() { return purchasePrice; }
    public void setPurchasePrice(BigDecimal purchasePrice) { this.purchasePrice = purchasePrice; }
    public boolean isPurchasedFromDealer() { return purchasedFromDealer; }
    public void setPurchasedFromDealer(boolean purchasedFromDealer) { this.purchasedFromDealer = purchasedFromDealer; }
    public String getDealerName() { return dealerName; }
    public void setDealerName(String dealerName) { this.dealerName = dealerName; }
    public Integer getCurrentMileage() { return currentMileage; }
    public void setCurrentMileage(Integer currentMileage) { this.currentMileage = currentMileage; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public List<String> getPhotoFilenames() { return photoFilenames; }
    public void setPhotoFilenames(List<String> photoFilenames) { this.photoFilenames = photoFilenames; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public BigDecimal getTotalMaintenanceCost() { return totalMaintenanceCost; }
    public void setTotalMaintenanceCost(BigDecimal totalMaintenanceCost) { this.totalMaintenanceCost = totalMaintenanceCost; }
    public Double getAverageMpg() { return averageMpg; }
    public void setAverageMpg(Double averageMpg) { this.averageMpg = averageMpg; }
    public Integer getMaintenanceCount() { return maintenanceCount; }
    public void setMaintenanceCount(Integer maintenanceCount) { this.maintenanceCount = maintenanceCount; }
    public String getNextServiceDue() { return nextServiceDue; }
    public void setNextServiceDue(String nextServiceDue) { this.nextServiceDue = nextServiceDue; }
    public boolean isHasOpenRecalls() { return hasOpenRecalls; }
    public void setHasOpenRecalls(boolean hasOpenRecalls) { this.hasOpenRecalls = hasOpenRecalls; }
    public boolean isShared() { return isShared; }
    public void setShared(boolean shared) { isShared = shared; }
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
    public List<VehicleAccessDTO> getSharedWith() { return sharedWith; }
    public void setSharedWith(List<VehicleAccessDTO> sharedWith) { this.sharedWith = sharedWith; }
}
