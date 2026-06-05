package com.receipttracker.service;

import com.receipttracker.model.MaintenanceRecord;
import com.receipttracker.model.MaintenanceType;
import com.receipttracker.model.Vehicle;
import com.receipttracker.repository.MaintenanceRecordRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Generates the recommended maintenance schedule for a vehicle based on
 * standard OEM-style intervals. No external API — built from well-known
 * service intervals published by major manufacturers.
 */
@Service
public class MaintenanceScheduleService {

    @Autowired
    private MaintenanceRecordRepository maintenanceRepo;

    // ── Interval definitions ─────────────────────────────────────────────────

    public record MaintenanceInterval(
            MaintenanceType type,
            String displayName,
            int mileInterval,
            int monthInterval,
            boolean critical,
            String note
    ) {}

    private static final List<MaintenanceInterval> INTERVALS = List.of(
        new MaintenanceInterval(MaintenanceType.OIL_CHANGE,            "Oil Change (Synthetic)",    7500,  12, true,  "7,500 mi or 12 months"),
        new MaintenanceInterval(MaintenanceType.TIRE_ROTATION,         "Tire Rotation",             7500,  6,  true,  "Every 7,500 mi or 6 months"),
        new MaintenanceInterval(MaintenanceType.AIR_FILTER,            "Engine Air Filter",        15000,  12, false, "Every 15,000–30,000 mi"),
        new MaintenanceInterval(MaintenanceType.CABIN_FILTER,          "Cabin Air Filter",         15000,  12, false, "Every 15,000–25,000 mi"),
        new MaintenanceInterval(MaintenanceType.BRAKE_INSPECTION,      "Brake Inspection",         15000,  12, true,  "Every 15,000 mi or annually"),
        new MaintenanceInterval(MaintenanceType.TRANSMISSION_SERVICE,  "Transmission Fluid",       30000,  24, false, "Every 30,000–60,000 mi"),
        new MaintenanceInterval(MaintenanceType.COOLANT_FLUSH,         "Coolant Flush",            30000,  24, false, "Every 30,000–50,000 mi"),
        new MaintenanceInterval(MaintenanceType.SPARK_PLUGS,           "Spark Plugs",              30000,  36, false, "Every 30,000–100,000 mi (varies by type)"),
        new MaintenanceInterval(MaintenanceType.FUEL_FILTER,           "Fuel Filter",              30000,  24, false, "Every 30,000 mi"),
        new MaintenanceInterval(MaintenanceType.SERPENTINE_BELT,       "Serpentine Belt",          60000,  60, true,  "Every 60,000–100,000 mi"),
        new MaintenanceInterval(MaintenanceType.TIMING_BELT,           "Timing Belt / Chain",      60000,  60, true,  "60,000–100,000 mi — check your owner's manual"),
        new MaintenanceInterval(MaintenanceType.WIPER_BLADES,          "Wiper Blades",             10000,   6, false, "Every 6–12 months"),
        new MaintenanceInterval(MaintenanceType.BATTERY_REPLACEMENT,   "Battery Check",             5000,  12, false, "Inspect yearly; replace every 3–5 years"),
        new MaintenanceInterval(MaintenanceType.WHEEL_ALIGNMENT,       "Wheel Alignment",          12000,  12, false, "Annually or when handling changes"),
        new MaintenanceInterval(MaintenanceType.INSPECTION_EMISSION,   "Inspection / Emissions",       0,  12, true,  "Annual state inspection")
    );

    // ── Schedule item ─────────────────────────────────────────────────────────

    public record ScheduleItem(
            MaintenanceType type,
            String displayName,
            int dueMileage,
            LocalDate dueByDate,
            boolean overdue,
            boolean dueSoon,          // within 500 miles or 30 days
            LocalDate lastPerformed,
            Integer lastMileage,
            boolean critical,
            String note
    ) {}

    // ── Public API ────────────────────────────────────────────────────────────

    public List<ScheduleItem> generateSchedule(Vehicle vehicle) {
        if (vehicle.getCurrentMileage() == null) return List.of();
        int currentMileage = vehicle.getCurrentMileage();
        LocalDate today = LocalDate.now();

        List<ScheduleItem> items = new ArrayList<>();

        for (MaintenanceInterval interval : INTERVALS) {
            if (interval.type() == MaintenanceType.INSPECTION_EMISSION) {
                // Date-only service — no mileage trigger
                Optional<MaintenanceRecord> last = maintenanceRepo
                        .findFirstByVehicleAndMaintenanceTypeOrderByServiceDateDescMileageDesc(vehicle, interval.type());
                LocalDate lastDate = last.map(MaintenanceRecord::getServiceDate).orElse(null);
                LocalDate dueDate = lastDate != null
                        ? lastDate.plusMonths(interval.monthInterval())
                        : today; // due now if never done

                boolean overdue = dueDate.isBefore(today);
                boolean soon    = !overdue && dueDate.isBefore(today.plusDays(30));

                items.add(new ScheduleItem(interval.type(), interval.displayName(),
                        0, dueDate, overdue, soon, lastDate, null, interval.critical(), interval.note()));
                continue;
            }

            Optional<MaintenanceRecord> last = maintenanceRepo
                    .findFirstByVehicleAndMaintenanceTypeOrderByServiceDateDescMileageDesc(vehicle, interval.type());

            int lastMileageVal = last.map(r -> r.getMileage() != null ? r.getMileage() : 0).orElse(0);
            LocalDate lastDate = last.map(MaintenanceRecord::getServiceDate).orElse(null);

            int dueMileage = lastMileageVal + interval.mileInterval();
            LocalDate dueDate = lastDate != null
                    ? lastDate.plusMonths(interval.monthInterval())
                    : (vehicle.getPurchaseDate() != null
                        ? vehicle.getPurchaseDate().plusMonths(interval.monthInterval())
                        : today);

            boolean overdueMiles = currentMileage >= dueMileage;
            boolean overdueDate  = dueDate.isBefore(today);
            boolean overdue = overdueMiles || overdueDate;

            boolean soonMiles = !overdueMiles && (dueMileage - currentMileage) <= 500;
            boolean soonDate  = !overdueDate && dueDate.isBefore(today.plusDays(30));
            boolean dueSoon = soonMiles || soonDate;

            items.add(new ScheduleItem(
                    interval.type(), interval.displayName(),
                    dueMileage, dueDate,
                    overdue, dueSoon,
                    lastDate, last.map(MaintenanceRecord::getMileage).orElse(null),
                    interval.critical(), interval.note()
            ));
        }

        // Sort: overdue critical first, then overdue, then due-soon, then upcoming
        items.sort(Comparator
                .comparing((ScheduleItem s) -> !s.overdue())
                .thenComparing(s -> !s.critical())
                .thenComparing(s -> !s.dueSoon())
                .thenComparing(ScheduleItem::dueMileage));

        return items;
    }

    /** Compact summary for vehicle card: the most urgent pending item. */
    public String nextServiceSummary(Vehicle vehicle) {
        List<ScheduleItem> schedule = generateSchedule(vehicle);
        return schedule.stream()
                .filter(s -> s.overdue() || s.dueSoon())
                .findFirst()
                .map(s -> {
                    if (s.overdue() && s.dueMileage() > 0) {
                        int overby = vehicle.getCurrentMileage() - s.dueMileage();
                        return s.displayName() + " overdue by " + overby + " mi";
                    }
                    if (s.overdue()) return s.displayName() + " overdue";
                    if (s.dueSoon() && s.dueMileage() > 0) {
                        int inMiles = s.dueMileage() - vehicle.getCurrentMileage();
                        return s.displayName() + " in " + inMiles + " mi";
                    }
                    return s.displayName() + " due " + s.dueByDate();
                })
                .orElse(null);
    }

    public List<MaintenanceInterval> getAllIntervals() {
        return INTERVALS;
    }
}
