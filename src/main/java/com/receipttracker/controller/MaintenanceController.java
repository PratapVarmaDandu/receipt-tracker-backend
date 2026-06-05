package com.receipttracker.controller;

import com.receipttracker.dto.MaintenanceRecordDTO;
import com.receipttracker.service.VehicleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/vehicles/{vehicleId}/maintenance")
public class MaintenanceController {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceController.class);

    @Autowired private VehicleService vehicleService;

    @PostMapping
    public ResponseEntity<?> add(
            @PathVariable Long vehicleId,
            @RequestPart("record") MaintenanceRecordDTO dto,
            @RequestPart(value = "receipt", required = false) MultipartFile receiptFile) {
        log.info("POST /api/vehicles/{}/maintenance type={}", vehicleId, dto.getMaintenanceType());
        try {
            return ResponseEntity.ok(vehicleService.addMaintenance(vehicleId, dto, receiptFile));
        } catch (Exception e) {
            log.error("!!! add maintenance failed: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<List<MaintenanceRecordDTO>> list(@PathVariable Long vehicleId) {
        return ResponseEntity.ok(vehicleService.getMaintenance(vehicleId));
    }

    @PutMapping("/{recordId}")
    public ResponseEntity<?> update(
            @PathVariable Long vehicleId,
            @PathVariable Long recordId,
            @RequestPart("record") MaintenanceRecordDTO dto,
            @RequestPart(value = "receipt", required = false) MultipartFile receiptFile) {
        try {
            return ResponseEntity.ok(vehicleService.updateMaintenance(vehicleId, recordId, dto, receiptFile));
        } catch (Exception e) {
            log.error("!!! update maintenance failed: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{recordId}")
    public ResponseEntity<?> delete(@PathVariable Long vehicleId, @PathVariable Long recordId) {
        try {
            vehicleService.deleteMaintenance(vehicleId, recordId);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{recordId}/receipt/{filename}")
    public ResponseEntity<?> getReceipt(@PathVariable Long vehicleId, @PathVariable String filename) {
        try {
            return ResponseEntity.ok()
                    .header("Content-Disposition", "inline; filename=\"" + filename + "\"")
                    .body(vehicleService.getMaintenanceReceipt(vehicleId, filename));
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}
