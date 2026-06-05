package com.receipttracker.service;

import com.receipttracker.config.StoragePathResolver;
import com.receipttracker.dto.*;
import com.receipttracker.model.ExpenseGroup;
import com.receipttracker.model.Receipt;
import com.receipttracker.model.ReceiptItem;
import com.receipttracker.model.User;
import com.receipttracker.model.Vehicle;
import com.receipttracker.repository.ExpenseGroupRepository;
import com.receipttracker.repository.GroupMemberRepository;
import com.receipttracker.repository.ReceiptRepository;
import com.receipttracker.repository.UserRepository;
import com.receipttracker.repository.VehicleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class ReceiptService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptService.class);

    @Autowired private ReceiptRepository receiptRepo;
    @Autowired private UserRepository userRepository;
    @Autowired private ExpenseGroupRepository groupRepo;
    @Autowired private GroupMemberRepository memberRepo;
    @Autowired private VehicleRepository vehicleRepo;
    @Autowired private OcrService ocrService;
    @Autowired private ReceiptParserService parserService;
    @Autowired private CashbackService cashbackService;
    @Autowired private ClaudeVisionService claudeVisionService;
    @Autowired private UserStorageService userStorageService;
    @Autowired private StoragePathResolver storagePathResolver;

    // ── User resolution ───────────────────────────────────────────────────────

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        OAuth2User principal = (OAuth2User) auth.getPrincipal();
        String googleId = principal.getAttribute("sub");
        log.trace("Resolving user for googleId={}", googleId);
        return userRepository.findByGoogleId(googleId)
                .orElseThrow(() -> {
                    log.error("User not found in database for googleId={}", googleId);
                    return new RuntimeException("Authenticated user not found in database");
                });
    }

    // ── Public API ────────────────────────────────────────────────────────────

    @Transactional
    public ReceiptDTO uploadAndProcess(MultipartFile file) throws IOException {
        long startTime = System.currentTimeMillis();
        log.info("TRANSACTION: uploadAndProcess START - file={}, size={}B", file.getOriginalFilename(), file.getSize());
        
        try {
            User user = currentUser();
            log.debug("  [1/4] Resolved user: id={}", user.getId());
            
            long ocrStart = System.currentTimeMillis();
            OcrService.OcrResult ocr = ocrService.processUpload(file);
            log.debug("  [2/5] OCR processing completed in {}ms, textLength={}",
                    System.currentTimeMillis() - ocrStart, ocr.extractedText().length());

            long parseStart = System.currentTimeMillis();
            File savedFile = storagePathResolver.asPath().resolve(ocr.savedFilename()).toFile();
            Optional<ParsedReceiptData> visionResult = claudeVisionService.analyze(savedFile);
            ParsedReceiptData parsed;
            if (visionResult.isPresent()) {
                log.info("  [3/5] Vision AI succeeded — skipping regex parser");
                parsed = visionResult.get();
            } else {
                log.info("  [3/5] Vision AI unavailable — using regex parser");
                parsed = parserService.parse(ocr.extractedText());
            }
            log.debug("  [4/5] Parsing completed in {}ms - store={}, total={}, items={}",
                    System.currentTimeMillis() - parseStart, parsed.getStoreName(), parsed.getTotal(),
                    parsed.getItems() != null ? parsed.getItems().size() : 0);

            Receipt receipt = new Receipt();
            receipt.setUser(user);
            receipt.setStoreName(parsed.getStoreName());
            receipt.setStoreType(parsed.getStoreType());
            receipt.setPurchaseDateTime(
                    parsed.getPurchaseDateTime() != null ? parsed.getPurchaseDateTime() : LocalDateTime.now());
            receipt.setCardType(parsed.getCardType());
            receipt.setCardBank(parsed.getCardBank());
            receipt.setLastFourDigits(parsed.getLastFourDigits());
            receipt.setPaymentCard(parsed.getPaymentCard());
            receipt.setSubtotal(parsed.getSubtotal());
            receipt.setTax(parsed.getTax());
            receipt.setTip(parsed.getTip());
            receipt.setTotal(parsed.getTotal());
            receipt.setReceiptType(parsed.getReceiptType());
            receipt.setRawOcrText(ocr.extractedText());
            receipt.setImageFileName(ocr.savedFilename());

            for (ParsedReceiptItem pi : parsed.getItems()) {
                ReceiptItem item = new ReceiptItem();
                item.setName(pi.getName());
                item.setDescription(pi.getDescription());
                item.setQuantity(pi.getQuantity());
                item.setUnitPrice(pi.getUnitPrice());
                item.setTotalPrice(pi.getTotalPrice());
                item.setCategory(pi.getCategory());
                item.setReceipt(receipt);
                receipt.getItems().add(item);
            }

            long saveStart = System.currentTimeMillis();
            Receipt saved = receiptRepo.save(receipt);
            log.debug("  [5/5] Database save completed in {}ms - receiptId={}",
                    System.currentTimeMillis() - saveStart, saved.getId());

            // Move/upload file to configured storage. Non-fatal — receipt is already saved.
            UserStorageService.StorageResult storageResult =
                    userStorageService.finalizeStorage(user, ocr.savedFilename());
            log.info("  Storage finalized: status={}, path={}", storageResult.status(), storageResult.savedPath());

            ReceiptDTO result = toDTO(saved);
            result.setFileSaveStatus(storageResult.status());
            result.setFileSavedTo(storageResult.savedPath());
            long totalDuration = System.currentTimeMillis() - startTime;
            log.info("TRANSACTION: uploadAndProcess COMMITTED - receiptId={}, duration={}ms, " +
                    "store={}, total={}, items={}", 
                    saved.getId(), totalDuration, parsed.getStoreName(), parsed.getTotal(),
                    parsed.getItems() != null ? parsed.getItems().size() : 0);
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("TRANSACTION: uploadAndProcess ROLLED_BACK after {}ms - error={}, msg={}", 
                    duration, e.getClass().getSimpleName(), e.getMessage(), e);
            throw e;
        }
    }

    @Transactional
    public ReceiptDTO saveManual(ReceiptDTO dto) {
        long startTime = System.currentTimeMillis();
        log.info("TRANSACTION: saveManual START - store={}, total={}", dto.getStoreName(), dto.getTotal());
        
        try {
            User user = currentUser();
            Receipt receipt = fromDTO(dto);
            receipt.setUser(user);
            
            Receipt saved = receiptRepo.save(receipt);
            long duration = System.currentTimeMillis() - startTime;
            ReceiptDTO result = toDTO(saved);
            
            log.info("TRANSACTION: saveManual COMMITTED - receiptId={}, duration={}ms", saved.getId(), duration);
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("TRANSACTION: saveManual ROLLED_BACK after {}ms - store={}, error={}", 
                    duration, dto.getStoreName(), e.getMessage(), e);
            throw e;
        }
    }

    @Transactional
    public ReceiptDTO update(Long id, ReceiptDTO dto) {
        long startTime = System.currentTimeMillis();
        log.info("TRANSACTION: update START - receiptId={}, store={}, total={}", id, dto.getStoreName(), dto.getTotal());
        
        try {
            Receipt existing = receiptRepo.findById(id)
                    .orElseThrow(() -> {
                        log.error("Receipt not found for update: id={}", id);
                        return new RuntimeException("Receipt not found: " + id);
                    });
            
            log.debug("  Found receipt for update - previousStore={}", existing.getStoreName());
            
            existing.setStoreName(dto.getStoreName());
            existing.setStoreType(dto.getStoreType());
            existing.setPurchaseDateTime(dto.getPurchaseDateTime());
            existing.setCardType(dto.getCardType());
            existing.setCardBank(dto.getCardBank());
            existing.setLastFourDigits(dto.getLastFourDigits());
            existing.setPaymentCard(dto.getPaymentCard());
            existing.setSubtotal(dto.getSubtotal());
            existing.setTax(dto.getTax());
            existing.setTip(dto.getTip());
            existing.setTotal(dto.getTotal());

            int itemCountOld = existing.getItems().size();
            existing.getItems().clear();
            if (dto.getItems() != null) {
                for (ReceiptItemDTO ri : dto.getItems()) {
                    ReceiptItem item = new ReceiptItem();
                    item.setName(ri.getName());
                    item.setDescription(ri.getDescription());
                    item.setQuantity(ri.getQuantity());
                    item.setUnitPrice(ri.getUnitPrice());
                    item.setTotalPrice(ri.getTotalPrice());
                    item.setCategory(ri.getCategory());
                    item.setTaxable(ri.isTaxable());
                    item.setReceipt(existing);
                    existing.getItems().add(item);
                }
            }
            
            Receipt saved = receiptRepo.save(existing);
            long duration = System.currentTimeMillis() - startTime;
            
            log.info("TRANSACTION: update COMMITTED - receiptId={}, itemsChanged={}→{}, duration={}ms", 
                    id, itemCountOld, existing.getItems().size(), duration);
            return toDTO(saved);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("TRANSACTION: update ROLLED_BACK after {}ms - receiptId={}, error={}", 
                    duration, id, e.getMessage(), e);
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public List<ReceiptDTO> getAll() {
        log.trace(">>> getAll() - Fetching all receipts for user");
        long startTime = System.currentTimeMillis();
        try {
            User user = currentUser();
            List<ReceiptDTO> results = receiptRepo.findByUserOrDemo(user)
                    .stream().map(this::toDTO).collect(Collectors.toList());
            long duration = System.currentTimeMillis() - startTime;
            log.debug("<<< getAll() - Retrieved {} receipts in {}ms", results.size(), duration);
            return results;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("!!! getAll() failed after {}ms: {}", duration, e.getMessage(), e);
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public ReceiptDTO getById(Long id) {
        log.trace(">>> getById({}) - Fetching single receipt", id);
        long startTime = System.currentTimeMillis();
        try {
            ReceiptDTO result = receiptRepo.findById(id).map(this::toDTO)
                    .orElseThrow(() -> {
                        log.warn("Receipt not found: id={}", id);
                        return new RuntimeException("Receipt not found: " + id);
                    });
            long duration = System.currentTimeMillis() - startTime;
            log.debug("<<< getById({}) - Retrieved in {}ms", id, duration);
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("!!! getById({}) failed after {}ms: {}", id, duration, e.getMessage(), e);
            throw e;
        }
    }

    @Transactional
    public ReceiptDTO addToGroup(Long receiptId, Long groupId) {
        log.info("TRANSACTION: addToGroup START - receiptId={} groupId={}", receiptId, groupId);
        User caller = currentUser();

        Receipt receipt = receiptRepo.findById(receiptId)
                .orElseThrow(() -> new RuntimeException("Receipt not found: " + receiptId));

        if (receipt.getUser() == null || !receipt.getUser().getId().equals(caller.getId())) {
            throw new RuntimeException("You do not own this receipt");
        }

        if (groupId == null) {
            receipt.setGroup(null);
            log.info("<<< addToGroup: removed group from receiptId={}", receiptId);
        } else {
            ExpenseGroup group = groupRepo.findById(groupId)
                    .orElseThrow(() -> new RuntimeException("Group not found: " + groupId));
            if (!memberRepo.existsByGroupAndUser(group, caller)) {
                throw new RuntimeException("You are not a member of this group");
            }
            receipt.setGroup(group);
            log.info("<<< addToGroup: assigned receiptId={} to groupId={}", receiptId, groupId);
        }

        return toDTO(receiptRepo.save(receipt));
    }

    @Transactional
    public ReceiptDTO linkToVehicle(Long receiptId, Long vehicleId) {
        log.info("TRANSACTION: linkToVehicle START - receiptId={} vehicleId={}", receiptId, vehicleId);
        User caller = currentUser();
        Receipt receipt = receiptRepo.findById(receiptId)
                .orElseThrow(() -> new RuntimeException("Receipt not found: " + receiptId));
        if (receipt.getUser() == null || !receipt.getUser().getId().equals(caller.getId())) {
            throw new RuntimeException("You do not own this receipt");
        }
        if (vehicleId == null) {
            receipt.setVehicle(null);
            log.info("<<< linkToVehicle: removed vehicle link from receiptId={}", receiptId);
        } else {
            Vehicle vehicle = vehicleRepo.findById(vehicleId)
                    .orElseThrow(() -> new RuntimeException("Vehicle not found: " + vehicleId));
            if (!vehicle.getUser().getId().equals(caller.getId())) {
                throw new RuntimeException("You do not own this vehicle");
            }
            receipt.setVehicle(vehicle);
            log.info("<<< linkToVehicle: linked receiptId={} to vehicleId={}", receiptId, vehicleId);
        }
        return toDTO(receiptRepo.save(receipt));
    }

    @Transactional
    public void delete(Long id) {
        long startTime = System.currentTimeMillis();
        log.info("TRANSACTION: delete START - receiptId={}", id);

        try {
            Receipt existing = receiptRepo.findById(id)
                    .orElseThrow(() -> {
                        log.error("Receipt not found for delete: id={}", id);
                        return new RuntimeException("Receipt not found: " + id);
                    });

            String imageFileName = existing.getImageFileName();
            log.debug("  Deleting receipt - store={}, total={}, file={}", existing.getStoreName(), existing.getTotal(), imageFileName);
            receiptRepo.deleteById(id);

            // Delete the receipt file from storage (non-fatal — DB record is already gone)
            if (imageFileName != null && !imageFileName.isBlank()) {
                User user = currentUser();
                userStorageService.deleteFile(user, imageFileName);
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("TRANSACTION: delete COMMITTED - receiptId={}, duration={}ms", id, duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("TRANSACTION: delete ROLLED_BACK after {}ms - receiptId={}, error={}",
                    duration, id, e.getMessage(), e);
            throw e;
        }
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    public ReceiptDTO toDTO(Receipt r) {
        ReceiptDTO dto = new ReceiptDTO();
        dto.setId(r.getId());
        dto.setStoreName(r.getStoreName());
        dto.setStoreType(r.getStoreType());
        dto.setReceiptType(r.getReceiptType());
        dto.setPurchaseDateTime(r.getPurchaseDateTime());
        dto.setCardType(r.getCardType());
        dto.setCardBank(r.getCardBank());
        dto.setLastFourDigits(r.getLastFourDigits());
        dto.setPaymentCard(r.getPaymentCard());
        dto.setSubtotal(r.getSubtotal());
        dto.setTax(r.getTax());
        dto.setTip(r.getTip());
        dto.setTotal(r.getTotal());
        dto.setImageFileName(r.getImageFileName());
        dto.setUploadedAt(r.getUploadedAt());

        if (r.getGroup() != null) {
            dto.setGroupId(r.getGroup().getId());
            dto.setGroupName(r.getGroup().getName());
        }

        if (r.getVehicle() != null) {
            dto.setVehicleId(r.getVehicle().getId());
            dto.setVehicleName(r.getVehicle().getModelYear() + " " + r.getVehicle().getMake() + " " + r.getVehicle().getModel());
        }

        if (r.getItems() != null) {
            dto.setItems(r.getItems().stream().map(this::toItemDTO).collect(Collectors.toList()));
        }

        BigDecimal earned    = cashbackService.calculateCashbackForReceipt(r);
        BigDecimal potential = cashbackService.calculateBestPossibleCashback(r);
        dto.setCashbackEarned(earned);
        dto.setPotentialCashback(potential);
        dto.setBestCard(cashbackService.bestCardDisplay(r.getStoreType()));
        dto.setBestCardRate(cashbackService.bestCardRate(r.getStoreType()));
        return dto;
    }

    private ReceiptItemDTO toItemDTO(ReceiptItem item) {
        ReceiptItemDTO dto = new ReceiptItemDTO();
        dto.setId(item.getId());
        dto.setName(item.getName());
        dto.setDescription(item.getDescription());
        dto.setQuantity(item.getQuantity());
        dto.setUnitPrice(item.getUnitPrice());
        dto.setTotalPrice(item.getTotalPrice());
        dto.setCategory(item.getCategory());
        dto.setTaxable(item.isTaxable());
        return dto;
    }

    private Receipt fromDTO(ReceiptDTO dto) {
        Receipt r = new Receipt();
        r.setStoreName(dto.getStoreName());
        r.setStoreType(dto.getStoreType());
        r.setPurchaseDateTime(dto.getPurchaseDateTime());
        r.setCardType(dto.getCardType());
        r.setCardBank(dto.getCardBank());
        r.setLastFourDigits(dto.getLastFourDigits());
        r.setPaymentCard(dto.getPaymentCard());
        r.setSubtotal(dto.getSubtotal());
        r.setTax(dto.getTax());
        r.setTip(dto.getTip());
        r.setTotal(dto.getTotal());

        if (dto.getItems() != null) {
            for (ReceiptItemDTO ri : dto.getItems()) {
                ReceiptItem item = new ReceiptItem();
                item.setName(ri.getName());
                item.setDescription(ri.getDescription());
                item.setQuantity(ri.getQuantity());
                item.setUnitPrice(ri.getUnitPrice());
                item.setTotalPrice(ri.getTotalPrice());
                item.setCategory(ri.getCategory());
                item.setTaxable(ri.isTaxable());
                item.setReceipt(r);
                r.getItems().add(item);
            }
        }
        return r;
    }
}
