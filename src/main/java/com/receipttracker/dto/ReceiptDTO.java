package com.receipttracker.dto;

import com.receipttracker.model.ReceiptType;
import com.receipttracker.model.StoreType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class ReceiptDTO {
    private Long id;
    private String storeName;
    private StoreType storeType;
    private ReceiptType receiptType;
    private LocalDateTime purchaseDateTime;
    private String cardType;
    private String cardBank;
    private String lastFourDigits;
    private String paymentCard;
    private BigDecimal subtotal;
    private BigDecimal tax;
    private BigDecimal tip;
    private BigDecimal total;
    private String imageFileName;
    private LocalDateTime uploadedAt;
    private String fileSaveStatus;
    private String fileSavedTo;
    private List<ReceiptItemDTO> items;
    private BigDecimal cashbackEarned;
    private BigDecimal potentialCashback;
    private String bestCard;
    private String bestCardRate;
    private Long groupId;
    private String groupName;
    private Long vehicleId;
    private String vehicleName;
    private String vehicleCategory;

    public Long getGroupId() { return groupId; }
    public void setGroupId(Long groupId) { this.groupId = groupId; }
    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }
    public Long getVehicleId() { return vehicleId; }
    public void setVehicleId(Long vehicleId) { this.vehicleId = vehicleId; }
    public String getVehicleName() { return vehicleName; }
    public void setVehicleName(String vehicleName) { this.vehicleName = vehicleName; }
    public String getVehicleCategory() { return vehicleCategory; }
    public void setVehicleCategory(String vehicleCategory) { this.vehicleCategory = vehicleCategory; }
}
