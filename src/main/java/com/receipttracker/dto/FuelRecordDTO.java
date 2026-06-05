package com.receipttracker.dto;

import com.receipttracker.model.FuelType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public class FuelRecordDTO {
    private Long id;
    private Long vehicleId;
    private LocalDate fillDate;
    private Integer odometer;
    private BigDecimal gallons;
    private BigDecimal pricePerGallon;
    private BigDecimal totalCost;
    private FuelType fuelType;
    private boolean fullTank;
    private String stationName;
    private String notes;
    private Double mpg;    // computed from consecutive full-tank fills
    private LocalDateTime createdAt;

    public FuelRecordDTO() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getVehicleId() { return vehicleId; }
    public void setVehicleId(Long vehicleId) { this.vehicleId = vehicleId; }
    public LocalDate getFillDate() { return fillDate; }
    public void setFillDate(LocalDate fillDate) { this.fillDate = fillDate; }
    public Integer getOdometer() { return odometer; }
    public void setOdometer(Integer odometer) { this.odometer = odometer; }
    public BigDecimal getGallons() { return gallons; }
    public void setGallons(BigDecimal gallons) { this.gallons = gallons; }
    public BigDecimal getPricePerGallon() { return pricePerGallon; }
    public void setPricePerGallon(BigDecimal pricePerGallon) { this.pricePerGallon = pricePerGallon; }
    public BigDecimal getTotalCost() { return totalCost; }
    public void setTotalCost(BigDecimal totalCost) { this.totalCost = totalCost; }
    public FuelType getFuelType() { return fuelType; }
    public void setFuelType(FuelType fuelType) { this.fuelType = fuelType; }
    public boolean isFullTank() { return fullTank; }
    public void setFullTank(boolean fullTank) { this.fullTank = fullTank; }
    public String getStationName() { return stationName; }
    public void setStationName(String stationName) { this.stationName = stationName; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Double getMpg() { return mpg; }
    public void setMpg(Double mpg) { this.mpg = mpg; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
