package com.receipttracker.controller;

import com.receipttracker.dto.FuelRecordDTO;
import com.receipttracker.service.VehicleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/vehicles/{vehicleId}/fuel")
public class FuelController {

    private static final Logger log = LoggerFactory.getLogger(FuelController.class);

    @Autowired private VehicleService vehicleService;

    @PostMapping
    public ResponseEntity<?> add(@PathVariable Long vehicleId, @RequestBody FuelRecordDTO dto) {
        log.info("POST /api/vehicles/{}/fuel odometer={}", vehicleId, dto.getOdometer());
        try {
            return ResponseEntity.ok(vehicleService.addFuel(vehicleId, dto));
        } catch (Exception e) {
            log.error("!!! add fuel failed: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<List<FuelRecordDTO>> list(@PathVariable Long vehicleId) {
        return ResponseEntity.ok(vehicleService.getFuel(vehicleId));
    }

    @PutMapping("/{recordId}")
    public ResponseEntity<?> update(
            @PathVariable Long vehicleId,
            @PathVariable Long recordId,
            @RequestBody FuelRecordDTO dto) {
        try {
            return ResponseEntity.ok(vehicleService.updateFuel(vehicleId, recordId, dto));
        } catch (Exception e) {
            log.error("!!! update fuel failed: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{recordId}")
    public ResponseEntity<?> delete(@PathVariable Long vehicleId, @PathVariable Long recordId) {
        try {
            vehicleService.deleteFuel(vehicleId, recordId);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
