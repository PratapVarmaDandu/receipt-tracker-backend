package com.receipttracker.immigration.controller;

import com.receipttracker.immigration.dto.ScanResult;
import com.receipttracker.immigration.service.ImmDocumentScanService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/immigration")
public class ImmDocumentScanController {

    @Autowired
    private ImmDocumentScanService scanService;

    /**
     * Beneficiary scans their own document to pre-fill canonical profile fields.
     * Auth required — any authenticated user.
     * Returns extracted fields only — NOTHING is saved.
     */
    @PostMapping(value = "/profile/scan", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ScanResult> scanForProfile(
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(scanService.scanForProfile(file));
    }

    /**
     * Attorney / paralegal scans a case document (I-797, I-94, etc.)
     * Requires WRITE_CASE grant on the case.
     * Returns extracted fields only — NOTHING is saved.
     */
    @PostMapping(value = "/cases/{caseId}/scan-document",
                 consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ScanResult> scanForCase(
            @PathVariable Long caseId,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(scanService.scanForCase(caseId, file));
    }
}
