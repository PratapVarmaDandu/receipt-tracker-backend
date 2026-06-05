package com.receipttracker.dto;

import java.time.LocalDateTime;

public class VehicleAccessDTO {

    private Long id;
    private String inviteeEmail;
    private String inviteeName;   // populated once ACCEPTED
    private String status;        // PENDING | ACCEPTED | REVOKED
    private LocalDateTime grantedAt;

    // Vehicle info — populated on the invite-join endpoint so the page can show it
    private Long vehicleId;
    private String vehicleName;   // "{year} {make} {model}"
    private String ownerName;

    public VehicleAccessDTO() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getInviteeEmail() { return inviteeEmail; }
    public void setInviteeEmail(String inviteeEmail) { this.inviteeEmail = inviteeEmail; }
    public String getInviteeName() { return inviteeName; }
    public void setInviteeName(String inviteeName) { this.inviteeName = inviteeName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getGrantedAt() { return grantedAt; }
    public void setGrantedAt(LocalDateTime grantedAt) { this.grantedAt = grantedAt; }
    public Long getVehicleId() { return vehicleId; }
    public void setVehicleId(Long vehicleId) { this.vehicleId = vehicleId; }
    public String getVehicleName() { return vehicleName; }
    public void setVehicleName(String vehicleName) { this.vehicleName = vehicleName; }
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
}
