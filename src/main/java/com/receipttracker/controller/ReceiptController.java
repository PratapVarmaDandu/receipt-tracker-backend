package com.receipttracker.controller;

import com.receipttracker.dto.ReceiptDTO;
import com.receipttracker.service.ReceiptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/receipts")
public class ReceiptController {

    private static final Logger log = LoggerFactory.getLogger(ReceiptController.class);

    @Autowired
    private ReceiptService receiptService;

    @GetMapping
    public ResponseEntity<List<ReceiptDTO>> getAll() {
        log.trace(">>> GET /api/receipts - Fetching all receipts");
        long startTime = System.currentTimeMillis();
        try {
            List<ReceiptDTO> receipts = receiptService.getAll();
            long duration = System.currentTimeMillis() - startTime;
            log.debug("<<< GET /api/receipts completed - count={}, duration={}ms", receipts.size(), duration);
            return ResponseEntity.ok(receipts);
        } catch (Exception e) {
            log.error("!!! GET /api/receipts failed after {}ms: {}", System.currentTimeMillis() - startTime, e.getMessage(), e);
            throw e;
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<ReceiptDTO> getById(@PathVariable Long id) {
        log.trace(">>> GET /api/receipts/{} - Fetching receipt by id", id);
        long startTime = System.currentTimeMillis();
        try {
            ReceiptDTO receipt = receiptService.getById(id);
            long duration = System.currentTimeMillis() - startTime;
            log.debug("<<< GET /api/receipts/{} completed - store={}, total={}, duration={}ms", 
                id, receipt.getStoreName(), receipt.getTotal(), duration);
            return ResponseEntity.ok(receipt);
        } catch (Exception e) {
            log.error("!!! GET /api/receipts/{} failed after {}ms: {}", id, System.currentTimeMillis() - startTime, e.getMessage(), e);
            throw e;
        }
    }

    @PostMapping("/upload")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            log.warn("POST /api/receipts/upload - No file provided");
            return ResponseEntity.badRequest().body(Map.of("error", "No file provided"));
        }
        log.info("POST /api/receipts/upload - name={}, size={}B, type={}", 
                file.getOriginalFilename(), file.getSize(), file.getContentType());
        long startTime = System.currentTimeMillis();
        try {
            ReceiptDTO result = receiptService.uploadAndProcess(file);
            long duration = System.currentTimeMillis() - startTime;
            log.info("<<< POST /api/receipts/upload - SUCCESS: receiptId={}, store={}, total={}, duration={}ms", 
                    result.getId(), result.getStoreName(), result.getTotal(), duration);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("!!! POST /api/receipts/upload FAILED - file='{}', duration={}ms, error={}", 
                    file.getOriginalFilename(), duration, e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Upload failed: " + e.getMessage(),
                    "type",  e.getClass().getSimpleName()
            ));
        }
    }

    @PostMapping("/manual")
    public ResponseEntity<?> createManual(@RequestBody ReceiptDTO dto) {
        log.info("POST /api/receipts/manual - store={}, total={}, items={}", 
                dto.getStoreName(), dto.getTotal(), dto.getItems() != null ? dto.getItems().size() : 0);
        long startTime = System.currentTimeMillis();
        try {
            ReceiptDTO result = receiptService.saveManual(dto);
            long duration = System.currentTimeMillis() - startTime;
            log.info("<<< POST /api/receipts/manual - SUCCESS: receiptId={}, duration={}ms", result.getId(), duration);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("!!! POST /api/receipts/manual FAILED - store='{}', duration={}ms, error={}", 
                    dto.getStoreName(), duration, e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody ReceiptDTO dto) {
        log.info("PUT /api/receipts/{} - store={}, total={}", id, dto.getStoreName(), dto.getTotal());
        long startTime = System.currentTimeMillis();
        try {
            ReceiptDTO result = receiptService.update(id, dto);
            long duration = System.currentTimeMillis() - startTime;
            log.info("<<< PUT /api/receipts/{} - SUCCESS: duration={}ms", id, duration);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("!!! PUT /api/receipts/{} FAILED - duration={}ms, error={}", id, duration, e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        log.info("DELETE /api/receipts/{}", id);
        long startTime = System.currentTimeMillis();
        try {
            receiptService.delete(id);
            long duration = System.currentTimeMillis() - startTime;
            log.info("<<< DELETE /api/receipts/{} - SUCCESS: duration={}ms", id, duration);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("!!! DELETE /api/receipts/{} FAILED - duration={}ms, error={}", id, duration, e.getMessage(), e);
            throw e;
        }
    }
}
