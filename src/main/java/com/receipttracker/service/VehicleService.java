package com.receipttracker.service;

import com.receipttracker.config.StoragePathResolver;
import com.receipttracker.dto.FuelRecordDTO;
import com.receipttracker.dto.MaintenanceRecordDTO;
import com.receipttracker.dto.VehicleAccessDTO;
import com.receipttracker.dto.VehicleDTO;
import com.receipttracker.model.*;
import com.receipttracker.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class VehicleService {

    private static final Logger log = LoggerFactory.getLogger(VehicleService.class);

    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
        "image/jpeg", "image/png", "image/webp", "image/heic", "image/heif",
        "application/pdf"
    );

    @Value("${app.frontend.url:http://localhost:4200}")
    private String frontendUrl;

    @Autowired private VehicleRepository vehicleRepo;
    @Autowired private MaintenanceRecordRepository maintenanceRepo;
    @Autowired private FuelRecordRepository fuelRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private ReceiptRepository receiptRepo;
    @Autowired private VehicleAccessRepository accessRepo;
    @Autowired private StoragePathResolver storagePathResolver;
    @Autowired private MaintenanceScheduleService scheduleService;
    @Autowired private NhtsaApiService nhtsaService;
    @Autowired private VehicleReportService reportService;
    @Autowired private EmailService emailService;
    @Autowired private FeatureEntitlementService entitlement;

    // ── User resolution ───────────────────────────────────────────────────────

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        OAuth2User principal = (OAuth2User) auth.getPrincipal();
        String googleId = principal.getAttribute("sub");
        return userRepo.findByGoogleId(googleId)
                .orElseThrow(() -> new RuntimeException("Authenticated user not found"));
    }

    // ── Storage ───────────────────────────────────────────────────────────────

    private Path vehicleDir(Long vehicleId) throws IOException {
        Path dir = storagePathResolver.asPath()
                .resolve("vehicles").resolve(String.valueOf(vehicleId));
        Files.createDirectories(dir);
        return dir;
    }

    // ── Vehicle CRUD ──────────────────────────────────────────────────────────

    @Transactional
    public VehicleDTO create(VehicleDTO dto) {
        entitlement.requireFeature(AppFeature.GARAGE);
        User user = currentUser();
        Vehicle v = fromDTO(dto);
        v.setUser(user);
        return toDTO(vehicleRepo.save(v), false, null);
    }

    @Transactional(readOnly = true)
    public List<VehicleDTO> listMine() {
        entitlement.requireFeature(AppFeature.GARAGE);
        User user = currentUser();
        List<VehicleDTO> result = new ArrayList<>();

        // Owned vehicles
        vehicleRepo.findByUserOrderByModelYearDescMakeAsc(user)
                .stream().map(v -> toDTO(v, false, null)).forEach(result::add);

        // Vehicles shared with this user (ACCEPTED only)
        accessRepo.findByUserAndStatus(user, VehicleAccess.AccessStatus.ACCEPTED)
                .stream()
                .map(access -> toDTO(access.getVehicle(), true, access.getVehicle().getUser().getName()))
                .forEach(result::add);

        return result;
    }

    @Transactional(readOnly = true)
    public VehicleDTO getById(Long id) {
        User caller = currentUser();
        Vehicle v = requireCanView(id);
        boolean isShared = !v.getUser().getId().equals(caller.getId());
        VehicleDTO dto = toDTO(v, isShared, isShared ? v.getUser().getName() : null);
        if (!isShared) {
            dto.setSharedWith(accessRepo.findByVehicleOrderByGrantedAtDesc(v)
                    .stream().map(this::toAccessDTO).collect(Collectors.toList()));
        }
        return dto;
    }

    @Transactional
    public VehicleDTO update(Long id, VehicleDTO dto) {
        Vehicle v = requireOwned(id);
        applyDTO(v, dto);
        return toDTO(vehicleRepo.save(v), false, null);
    }

    @Transactional
    public void delete(Long id) throws IOException {
        Vehicle v = requireOwned(id);
        // Remove stored photos
        for (String fn : v.getPhotoFilenames()) {
            try {
                Files.deleteIfExists(vehicleDir(id).resolve(fn));
            } catch (IOException e) {
                log.warn("Failed to delete vehicle photo: {}", fn);
            }
        }
        vehicleRepo.delete(v);
        log.info("Deleted vehicle id={}", id);
    }

    // ── Photos ────────────────────────────────────────────────────────────────

    @Transactional
    public VehicleDTO addPhoto(Long vehicleId, MultipartFile file) throws IOException {
        Vehicle v = requireOwned(vehicleId);
        String ct = file.getContentType() != null ? file.getContentType().toLowerCase() : "";
        if (!ALLOWED_IMAGE_TYPES.contains(ct)) throw new RuntimeException("File type not allowed: " + ct);

        String ext = getExtension(file.getOriginalFilename());
        String stored = UUID.randomUUID() + "." + ext;
        Files.copy(file.getInputStream(), vehicleDir(vehicleId).resolve(stored));
        v.getPhotoFilenames().add(stored);
        return toDTO(vehicleRepo.save(v), false, null);
    }

    @Transactional
    public VehicleDTO removePhoto(Long vehicleId, String filename) throws IOException {
        Vehicle v = requireOwned(vehicleId);
        v.getPhotoFilenames().remove(filename);
        try { Files.deleteIfExists(vehicleDir(vehicleId).resolve(filename)); } catch (IOException ignored) {}
        return toDTO(vehicleRepo.save(v), false, null);
    }

    public Resource getPhoto(Long vehicleId, String filename) throws IOException {
        requireCanView(vehicleId);
        Path file = vehicleDir(vehicleId).resolve(filename);
        if (!Files.exists(file)) throw new RuntimeException("Photo not found");
        return new FileSystemResource(file);
    }

    // ── Maintenance ───────────────────────────────────────────────────────────

    @Transactional
    public MaintenanceRecordDTO addMaintenance(Long vehicleId, MaintenanceRecordDTO dto,
                                               MultipartFile receiptFile) throws IOException {
        Vehicle v = requireCanView(vehicleId);
        MaintenanceRecord r = new MaintenanceRecord();
        r.setVehicle(v);
        r.setMaintenanceType(dto.getMaintenanceType());
        r.setCustomDescription(dto.getCustomDescription());
        r.setServiceDate(dto.getServiceDate());
        r.setMileage(dto.getMileage());
        r.setCost(dto.getCost());
        r.setProvider(dto.getProvider());
        r.setNotes(dto.getNotes());

        if (dto.getLinkedReceiptId() != null) {
            receiptRepo.findById(dto.getLinkedReceiptId()).ifPresent(r::setLinkedReceipt);
        }

        if (receiptFile != null && !receiptFile.isEmpty()) {
            String ct = receiptFile.getContentType() != null ? receiptFile.getContentType().toLowerCase() : "";
            if (!ALLOWED_IMAGE_TYPES.contains(ct)) throw new RuntimeException("File type not allowed: " + ct);
            String ext = getExtension(receiptFile.getOriginalFilename());
            String stored = "maint_" + UUID.randomUUID() + "." + ext;
            Files.copy(receiptFile.getInputStream(), vehicleDir(vehicleId).resolve(stored));
            r.setReceiptFileName(stored);
        }

        // Update vehicle mileage if this record is the most recent
        if (dto.getMileage() != null && (v.getCurrentMileage() == null || dto.getMileage() > v.getCurrentMileage())) {
            v.setCurrentMileage(dto.getMileage());
            vehicleRepo.save(v);
        }

        return toMaintenanceDTO(maintenanceRepo.save(r));
    }

    @Transactional(readOnly = true)
    public List<MaintenanceRecordDTO> getMaintenance(Long vehicleId) {
        Vehicle v = requireCanView(vehicleId);
        return maintenanceRepo.findByVehicleOrderByServiceDateDescMileageDesc(v)
                .stream().map(this::toMaintenanceDTO).collect(Collectors.toList());
    }

    @Transactional
    public MaintenanceRecordDTO updateMaintenance(Long vehicleId, Long recordId,
                                                   MaintenanceRecordDTO dto,
                                                   MultipartFile receiptFile) throws IOException {
        requireCanView(vehicleId);
        MaintenanceRecord r = maintenanceRepo.findById(recordId)
                .orElseThrow(() -> new RuntimeException("Record not found: " + recordId));
        if (!r.getVehicle().getId().equals(vehicleId)) throw new RuntimeException("Access denied");
        if (r.getCreatedAt() != null && r.getCreatedAt().isBefore(LocalDateTime.now().minusDays(30))) {
            throw new RuntimeException("Edit window expired — records can only be edited within 30 days of creation");
        }
        if (dto.getMaintenanceType() != null) r.setMaintenanceType(dto.getMaintenanceType());
        if (dto.getCustomDescription() != null) r.setCustomDescription(dto.getCustomDescription());
        if (dto.getServiceDate() != null) r.setServiceDate(dto.getServiceDate());
        r.setMileage(dto.getMileage());
        r.setCost(dto.getCost());
        r.setProvider(dto.getProvider());
        r.setNotes(dto.getNotes());

        if (receiptFile != null && !receiptFile.isEmpty()) {
            String ct = receiptFile.getContentType() != null ? receiptFile.getContentType().toLowerCase() : "";
            if (!ALLOWED_IMAGE_TYPES.contains(ct)) throw new RuntimeException("File type not allowed: " + ct);
            if (r.getReceiptFileName() != null) {
                try { Files.deleteIfExists(vehicleDir(vehicleId).resolve(r.getReceiptFileName())); } catch (IOException ignored) {}
            }
            String ext = getExtension(receiptFile.getOriginalFilename());
            String stored = "maint_" + UUID.randomUUID() + "." + ext;
            Files.copy(receiptFile.getInputStream(), vehicleDir(vehicleId).resolve(stored));
            r.setReceiptFileName(stored);
        }

        Vehicle v = r.getVehicle();
        if (r.getMileage() != null && (v.getCurrentMileage() == null || r.getMileage() > v.getCurrentMileage())) {
            v.setCurrentMileage(r.getMileage());
            vehicleRepo.save(v);
        }
        return toMaintenanceDTO(maintenanceRepo.save(r));
    }

    @Transactional
    public void deleteMaintenance(Long vehicleId, Long recordId) {
        requireCanView(vehicleId);
        MaintenanceRecord r = maintenanceRepo.findById(recordId)
                .orElseThrow(() -> new RuntimeException("Record not found: " + recordId));
        if (!r.getVehicle().getId().equals(vehicleId)) throw new RuntimeException("Access denied");
        maintenanceRepo.delete(r);
    }

    public Resource getMaintenanceReceipt(Long vehicleId, String filename) throws IOException {
        requireCanView(vehicleId);
        Path file = vehicleDir(vehicleId).resolve(filename);
        if (!Files.exists(file)) throw new RuntimeException("File not found");
        return new FileSystemResource(file);
    }

    // ── Fuel log ──────────────────────────────────────────────────────────────

    @Transactional
    public FuelRecordDTO addFuel(Long vehicleId, FuelRecordDTO dto) {
        Vehicle v = requireCanView(vehicleId);
        FuelRecord f = new FuelRecord();
        f.setVehicle(v);
        f.setFillDate(dto.getFillDate());
        f.setOdometer(dto.getOdometer());
        f.setGallons(dto.getGallons());
        f.setPricePerGallon(dto.getPricePerGallon());
        f.setTotalCost(dto.getTotalCost());
        f.setFuelType(dto.getFuelType() != null ? dto.getFuelType() : FuelType.REGULAR);
        f.setFullTank(dto.isFullTank());
        f.setStationName(dto.getStationName());
        f.setNotes(dto.getNotes());

        // Update mileage
        if (dto.getOdometer() != null && (v.getCurrentMileage() == null || dto.getOdometer() > v.getCurrentMileage())) {
            v.setCurrentMileage(dto.getOdometer());
            vehicleRepo.save(v);
        }

        return toFuelDTO(fuelRepo.save(f), null);
    }

    @Transactional(readOnly = true)
    public List<FuelRecordDTO> getFuel(Long vehicleId) {
        Vehicle v = requireCanView(vehicleId);
        List<FuelRecord> records = fuelRepo.findByVehicleOrderByOdometerAsc(v);
        return computeMpg(records);
    }

    @Transactional
    public FuelRecordDTO updateFuel(Long vehicleId, Long recordId, FuelRecordDTO dto) {
        requireCanView(vehicleId);
        FuelRecord f = fuelRepo.findById(recordId)
                .orElseThrow(() -> new RuntimeException("Record not found: " + recordId));
        if (!f.getVehicle().getId().equals(vehicleId)) throw new RuntimeException("Access denied");
        if (f.getCreatedAt() != null && f.getCreatedAt().isBefore(LocalDateTime.now().minusDays(30))) {
            throw new RuntimeException("Edit window expired — records can only be edited within 30 days of creation");
        }
        if (dto.getFillDate() != null) f.setFillDate(dto.getFillDate());
        if (dto.getOdometer() != null) f.setOdometer(dto.getOdometer());
        if (dto.getGallons() != null) f.setGallons(dto.getGallons());
        f.setPricePerGallon(dto.getPricePerGallon());
        f.setTotalCost(dto.getTotalCost());
        if (dto.getFuelType() != null) f.setFuelType(dto.getFuelType());
        f.setFullTank(dto.isFullTank());
        f.setStationName(dto.getStationName());
        f.setNotes(dto.getNotes());

        Vehicle v = f.getVehicle();
        if (f.getOdometer() != null && (v.getCurrentMileage() == null || f.getOdometer() > v.getCurrentMileage())) {
            v.setCurrentMileage(f.getOdometer());
            vehicleRepo.save(v);
        }
        FuelRecord saved = fuelRepo.save(f);
        // Recompute MPG with full list so the returned record has accurate mpg
        List<FuelRecord> all = fuelRepo.findByVehicleOrderByOdometerAsc(v);
        return computeMpg(all).stream().filter(d -> d.getId().equals(saved.getId())).findFirst()
                .orElse(toFuelDTO(saved, null));
    }

    @Transactional
    public void deleteFuel(Long vehicleId, Long recordId) {
        requireCanView(vehicleId);
        FuelRecord f = fuelRepo.findById(recordId)
                .orElseThrow(() -> new RuntimeException("Record not found: " + recordId));
        if (!f.getVehicle().getId().equals(vehicleId)) throw new RuntimeException("Access denied");
        fuelRepo.delete(f);
    }

    // ── Maintenance schedule ──────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getSchedule(Long vehicleId) {
        Vehicle v = requireCanView(vehicleId);
        return scheduleService.generateSchedule(v).stream()
                .map(item -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("type",          item.type().name());
                    m.put("displayName",   item.displayName());
                    m.put("dueMileage",    item.dueMileage());
                    m.put("dueByDate",     item.dueByDate());
                    m.put("overdue",       item.overdue());
                    m.put("dueSoon",       item.dueSoon());
                    m.put("lastPerformed", item.lastPerformed());
                    m.put("lastMileage",   item.lastMileage());
                    m.put("critical",      item.critical());
                    m.put("note",          item.note());
                    return m;
                }).collect(Collectors.toList());
    }

    // ── PDF sale report ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public byte[] generateSaleReport(Long vehicleId) throws IOException {
        Vehicle v = requireOwned(vehicleId);
        List<MaintenanceRecord> maintenance = maintenanceRepo
                .findByVehicleOrderByServiceDateDescMileageDesc(v);
        List<FuelRecord> fuelRecords = fuelRepo.findByVehicleOrderByFillDateDesc(v);
        BigDecimal totalCost = maintenanceRepo.sumCostByVehicle(v);
        Double avgMpg = computeAverageMpg(fuelRepo.findByVehicleOrderByOdometerAsc(v));
        List<Map<String, String>> recalls = nhtsaService.getRecalls(v.getMake(), v.getModel(), v.getModelYear());

        return reportService.generateSaleReport(v, maintenance, fuelRecords, recalls, totalCost, avgMpg);
    }

    // ── Recalls ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, String>> getRecalls(Long vehicleId) {
        Vehicle v = requireCanView(vehicleId);
        return nhtsaService.getRecalls(v.getMake(), v.getModel(), v.getModelYear());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getVehicleReceipts(Long vehicleId) {
        Vehicle v = requireCanView(vehicleId);
        return receiptRepo.findByVehicle(v).stream()
                .map(r -> {
                    Map<String, Object> m = new java.util.HashMap<>();
                    m.put("id", r.getId());
                    m.put("storeName", r.getStoreName() != null ? r.getStoreName() : "");
                    m.put("total", r.getTotal() != null ? r.getTotal() : java.math.BigDecimal.ZERO);
                    m.put("purchaseDateTime", r.getPurchaseDateTime() != null ? r.getPurchaseDateTime().toString() : "");
                    m.put("storeType", r.getStoreType() != null ? r.getStoreType().name() : "");
                    m.put("vehicleCategory", r.getVehicleCategory() != null ? r.getVehicleCategory() : "");
                    return m;
                })
                .collect(Collectors.toList());
    }

    // ── Access guards ─────────────────────────────────────────────────────────

    /** Owner-only operations: delete vehicle, manage sharing, update metadata.
     *  Also enforces the GARAGE feature entitlement for every caller. */
    private Vehicle requireOwned(Long id) {
        entitlement.requireFeature(AppFeature.GARAGE);
        User caller = currentUser();
        Vehicle v = vehicleRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Vehicle not found: " + id));
        if (!v.getUser().getId().equals(caller.getId())) throw new RuntimeException("Access denied");
        return v;
    }

    /** Owner or any accepted shared user — for view and add/edit operations.
     *  Also enforces the GARAGE feature entitlement for every caller. */
    private Vehicle requireCanView(Long id) {
        entitlement.requireFeature(AppFeature.GARAGE);
        User caller = currentUser();
        Vehicle v = vehicleRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Vehicle not found: " + id));
        if (v.getUser().getId().equals(caller.getId())) return v;
        if (accessRepo.existsByVehicleAndUserAndStatus(v, caller, VehicleAccess.AccessStatus.ACCEPTED)) return v;
        throw new RuntimeException("Access denied");
    }

    // ── MPG calculation ────────────────────────────────────────────────────────

    private List<FuelRecordDTO> computeMpg(List<FuelRecord> records) {
        List<FuelRecordDTO> result = new ArrayList<>();
        FuelRecord prev = null;
        for (FuelRecord r : records) {
            Double mpg = null;
            if (prev != null && r.isFullTank() && prev.isFullTank()
                    && r.getGallons().compareTo(BigDecimal.ZERO) > 0) {
                int miles = r.getOdometer() - prev.getOdometer();
                if (miles > 0) {
                    mpg = miles / r.getGallons().doubleValue();
                }
            }
            result.add(toFuelDTO(r, mpg));
            if (r.isFullTank()) prev = r;
        }
        Collections.reverse(result); // newest first
        return result;
    }

    private Double computeAverageMpg(List<FuelRecord> records) {
        List<FuelRecordDTO> dtos = computeMpg(records);
        List<Double> mpgValues = dtos.stream()
                .filter(d -> d.getMpg() != null)
                .map(FuelRecordDTO::getMpg)
                .collect(Collectors.toList());
        if (mpgValues.isEmpty()) return null;
        return mpgValues.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    VehicleDTO toDTO(Vehicle v, boolean isShared, String ownerName) {
        VehicleDTO dto = new VehicleDTO();
        dto.setId(v.getId());
        dto.setMake(v.getMake());
        dto.setModel(v.getModel());
        dto.setModelYear(v.getModelYear());
        dto.setTrim(v.getTrim());
        dto.setVin(v.getVin());
        dto.setColor(v.getColor());
        dto.setLicensePlate(v.getLicensePlate());
        dto.setRegistrationState(v.getRegistrationState());
        dto.setTagExpirationDate(v.getTagExpirationDate());
        dto.setInsuranceProvider(v.getInsuranceProvider());
        dto.setInsurancePolicyNumber(v.getInsurancePolicyNumber());
        dto.setInsuranceExpiryDate(v.getInsuranceExpiryDate());
        dto.setPurchaseDate(v.getPurchaseDate());
        dto.setPurchasePrice(v.getPurchasePrice());
        dto.setPurchasedFromDealer(v.isPurchasedFromDealer());
        dto.setDealerName(v.getDealerName());
        dto.setCurrentMileage(v.getCurrentMileage());
        dto.setNotes(v.getNotes());
        dto.setPhotoFilenames(v.getPhotoFilenames());
        dto.setCreatedAt(v.getCreatedAt());
        dto.setUpdatedAt(v.getUpdatedAt());
        dto.setShared(isShared);
        dto.setOwnerName(ownerName);

        // Expiry status
        dto.setTagStatus(expiryStatus(v.getTagExpirationDate()));
        dto.setInsuranceStatus(expiryStatus(v.getInsuranceExpiryDate()));
        if (v.getTagExpirationDate() != null) {
            dto.setDaysUntilTagExpiry((int) ChronoUnit.DAYS.between(LocalDate.now(), v.getTagExpirationDate()));
        }

        // Stats
        dto.setTotalMaintenanceCost(maintenanceRepo.sumCostByVehicle(v));
        dto.setMaintenanceCount((int) maintenanceRepo.findByVehicleOrderByServiceDateDescMileageDesc(v).size());
        Double avg = computeAverageMpg(fuelRepo.findByVehicleOrderByOdometerAsc(v));
        if (avg != null) dto.setAverageMpg(Math.round(avg * 10.0) / 10.0);
        dto.setNextServiceDue(scheduleService.nextServiceSummary(v));

        return dto;
    }

    private VehicleAccessDTO toAccessDTO(VehicleAccess a) {
        VehicleAccessDTO dto = new VehicleAccessDTO();
        dto.setId(a.getId());
        dto.setInviteeEmail(a.getInviteeEmail());
        dto.setStatus(a.getStatus().name());
        dto.setGrantedAt(a.getGrantedAt());
        if (a.getUser() != null) dto.setInviteeName(a.getUser().getName());
        dto.setVehicleId(a.getVehicle().getId());
        dto.setVehicleName(a.getVehicle().getModelYear() + " " + a.getVehicle().getMake() + " " + a.getVehicle().getModel());
        dto.setOwnerName(a.getVehicle().getUser().getName());
        return dto;
    }

    // ── Vehicle sharing ───────────────────────────────────────────────────────

    @Transactional
    public VehicleAccessDTO inviteAccess(Long vehicleId, String email) {
        Vehicle v = requireOwned(vehicleId);
        String normalizedEmail = email.trim().toLowerCase();

        if (v.getUser().getEmail().equalsIgnoreCase(normalizedEmail)) {
            throw new RuntimeException("Cannot share a vehicle with yourself");
        }
        if (accessRepo.existsByVehicleAndInviteeEmailAndStatusNot(
                v, normalizedEmail, VehicleAccess.AccessStatus.REVOKED)) {
            throw new RuntimeException("An active invite already exists for " + normalizedEmail);
        }

        VehicleAccess access = new VehicleAccess();
        access.setVehicle(v);
        access.setInviteeEmail(normalizedEmail);
        access.setStatus(VehicleAccess.AccessStatus.PENDING);
        VehicleAccess saved = accessRepo.save(access);

        String vehicleName = v.getModelYear() + " " + v.getMake() + " " + v.getModel();
        String inviteUrl = frontendUrl + "/garage/join/" + saved.getInviteToken();
        emailService.sendVehicleShareInvite(normalizedEmail, v.getUser().getName(), vehicleName, inviteUrl);
        log.info("<<< inviteAccess: sent invite vehicleId={} to={}", vehicleId, normalizedEmail);

        return toAccessDTO(saved);
    }

    @Transactional(readOnly = true)
    public List<VehicleAccessDTO> listAccess(Long vehicleId) {
        Vehicle v = requireOwned(vehicleId);
        return accessRepo.findByVehicleOrderByGrantedAtDesc(v)
                .stream().map(this::toAccessDTO).collect(Collectors.toList());
    }

    @Transactional
    public void revokeAccess(Long vehicleId, Long accessId) {
        requireOwned(vehicleId);
        VehicleAccess access = accessRepo.findById(accessId)
                .orElseThrow(() -> new RuntimeException("Access record not found: " + accessId));
        if (!access.getVehicle().getId().equals(vehicleId)) throw new RuntimeException("Access denied");
        access.setStatus(VehicleAccess.AccessStatus.REVOKED);
        accessRepo.save(access);
        log.info("<<< revokeAccess: vehicleId={} accessId={}", vehicleId, accessId);
    }

    @Transactional(readOnly = true)
    public VehicleAccessDTO getInviteByToken(String token) {
        VehicleAccess access = accessRepo.findByInviteToken(token)
                .orElseThrow(() -> new RuntimeException("Invite not found"));
        if (access.getStatus() == VehicleAccess.AccessStatus.REVOKED) {
            throw new RuntimeException("This invite has been revoked");
        }
        return toAccessDTO(access);
    }

    @Transactional
    public VehicleAccessDTO acceptInvite(String token) {
        User caller = currentUser();
        VehicleAccess access = accessRepo.findByInviteToken(token)
                .orElseThrow(() -> new RuntimeException("Invite not found"));
        if (access.getStatus() == VehicleAccess.AccessStatus.REVOKED) {
            throw new RuntimeException("This invite has been revoked");
        }
        if (access.getStatus() == VehicleAccess.AccessStatus.ACCEPTED) {
            return toAccessDTO(access); // already accepted — idempotent
        }
        if (!caller.getEmail().equalsIgnoreCase(access.getInviteeEmail())) {
            throw new RuntimeException("This invite is not for your account (" + caller.getEmail() + ")");
        }
        access.setUser(caller);
        access.setStatus(VehicleAccess.AccessStatus.ACCEPTED);
        log.info("<<< acceptInvite: vehicleId={} userId={}", access.getVehicle().getId(), caller.getId());
        return toAccessDTO(accessRepo.save(access));
    }

    private Vehicle fromDTO(VehicleDTO dto) {
        Vehicle v = new Vehicle();
        applyDTO(v, dto);
        return v;
    }

    private void applyDTO(Vehicle v, VehicleDTO dto) {
        if (dto.getMake() != null)  v.setMake(dto.getMake());
        if (dto.getModel() != null) v.setModel(dto.getModel());
        if (dto.getModelYear() != null)  v.setModelYear(dto.getModelYear());
        v.setTrim(dto.getTrim());
        v.setVin(dto.getVin());
        v.setColor(dto.getColor());
        v.setLicensePlate(dto.getLicensePlate());
        v.setRegistrationState(dto.getRegistrationState());
        v.setTagExpirationDate(dto.getTagExpirationDate());
        v.setInsuranceProvider(dto.getInsuranceProvider());
        v.setInsurancePolicyNumber(dto.getInsurancePolicyNumber());
        v.setInsuranceExpiryDate(dto.getInsuranceExpiryDate());
        v.setPurchaseDate(dto.getPurchaseDate());
        v.setPurchasePrice(dto.getPurchasePrice());
        v.setPurchasedFromDealer(dto.isPurchasedFromDealer());
        v.setDealerName(dto.getDealerName());
        if (dto.getCurrentMileage() != null) v.setCurrentMileage(dto.getCurrentMileage());
        v.setNotes(dto.getNotes());
    }

    private MaintenanceRecordDTO toMaintenanceDTO(MaintenanceRecord r) {
        MaintenanceRecordDTO dto = new MaintenanceRecordDTO();
        dto.setId(r.getId());
        dto.setVehicleId(r.getVehicle().getId());
        dto.setMaintenanceType(r.getMaintenanceType());
        dto.setCustomDescription(r.getCustomDescription());
        dto.setDisplayLabel(r.getCustomDescription() != null && !r.getCustomDescription().isBlank()
                ? r.getCustomDescription()
                : r.getMaintenanceType().name().replace('_', ' ')
                        .substring(0, 1).toUpperCase()
                        + r.getMaintenanceType().name().replace('_', ' ').substring(1).toLowerCase());
        dto.setServiceDate(r.getServiceDate());
        dto.setMileage(r.getMileage());
        dto.setCost(r.getCost());
        dto.setProvider(r.getProvider());
        dto.setNotes(r.getNotes());
        if (r.getLinkedReceipt() != null) dto.setLinkedReceiptId(r.getLinkedReceipt().getId());
        dto.setReceiptFileName(r.getReceiptFileName());
        dto.setCreatedAt(r.getCreatedAt());
        return dto;
    }

    private FuelRecordDTO toFuelDTO(FuelRecord f, Double mpg) {
        FuelRecordDTO dto = new FuelRecordDTO();
        dto.setId(f.getId());
        dto.setVehicleId(f.getVehicle().getId());
        dto.setFillDate(f.getFillDate());
        dto.setOdometer(f.getOdometer());
        dto.setGallons(f.getGallons());
        dto.setPricePerGallon(f.getPricePerGallon());
        dto.setTotalCost(f.getTotalCost());
        dto.setFuelType(f.getFuelType());
        dto.setFullTank(f.isFullTank());
        dto.setStationName(f.getStationName());
        dto.setNotes(f.getNotes());
        dto.setMpg(mpg != null ? Math.round(mpg * 10.0) / 10.0 : null);
        dto.setCreatedAt(f.getCreatedAt());
        return dto;
    }

    private String expiryStatus(LocalDate date) {
        if (date == null) return "UNKNOWN";
        long days = ChronoUnit.DAYS.between(LocalDate.now(), date);
        if (days < 0)    return "EXPIRED";
        if (days <= 30)  return "EXPIRING_SOON";
        return "VALID";
    }

    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) return "jpg";
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }
}
