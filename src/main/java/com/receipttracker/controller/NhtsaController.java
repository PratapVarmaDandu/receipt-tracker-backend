package com.receipttracker.controller;

import com.receipttracker.service.NhtsaApiService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/nhtsa")
public class NhtsaController {

    @Autowired private NhtsaApiService nhtsaService;

    /** Get all car makes — cached 24h. */
    @GetMapping("/makes")
    public ResponseEntity<List<String>> getMakes() {
        return ResponseEntity.ok(nhtsaService.getMakes());
    }

    /** Get models for a given make + year. */
    @GetMapping("/models")
    public ResponseEntity<List<String>> getModels(
            @RequestParam String make,
            @RequestParam int year) {
        return ResponseEntity.ok(nhtsaService.getModels(make, year));
    }

    /** Decode a VIN. */
    @GetMapping("/vin/{vin}")
    public ResponseEntity<Map<String, String>> decodeVin(
            @PathVariable String vin,
            @RequestParam(required = false) Integer year) {
        return ResponseEntity.ok(nhtsaService.decodeVin(vin, year));
    }
}
