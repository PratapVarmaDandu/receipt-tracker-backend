package com.receipttracker.controller;

import com.receipttracker.model.AppFeature;
import com.receipttracker.service.FeatureEntitlementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/me")
public class MeFeatureController {

    @Autowired private FeatureEntitlementService entitlementService;

    @GetMapping("/features")
    public ResponseEntity<?> myFeatures() {
        try {
            List<String> names = entitlementService.getMyFeatures().stream()
                    .map(AppFeature::name)
                    .sorted()
                    .toList();
            return ResponseEntity.ok(names);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
