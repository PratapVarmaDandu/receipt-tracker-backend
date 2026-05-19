package com.receipttracker.service;

import com.receipttracker.model.Receipt;
import com.receipttracker.model.ReceiptItem;
import com.receipttracker.model.StoreType;
import com.receipttracker.repository.ReceiptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Seeds demo receipts on first run (when the receipts table is empty).
 * These are visible to all logged-in users as shared demo data.
 */
@Component
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    @Autowired
    private ReceiptRepository receiptRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (receiptRepository.count() > 0) {
            log.info("Database already seeded — skipping demo data.");
            return;
        }
        log.info("Empty database detected — seeding demo receipts...");
        seedCostco();
        seedGasStation();
        seedRestaurant();
        seedGrocery();
        log.info("Seeded 4 demo receipts.");
    }

    private void seedCostco() {
        Receipt r = new Receipt();
        r.setStoreName("Costco Wholesale");
        r.setStoreType(StoreType.COSTCO);
        r.setPurchaseDateTime(LocalDateTime.now().minusDays(3).withHour(11).withMinute(25));
        r.setCardType("VISA");
        r.setCardBank("CITI");
        r.setLastFourDigits("7261");
        r.setPaymentCard("CITI_VISA_7261");
        r.setSubtotal(new BigDecimal("85.43"));
        r.setTax(new BigDecimal("0.00"));
        r.setTotal(new BigDecimal("85.43"));

        addItem(r, "KS Greek Yogurt 3 lb",  1, "12.99");
        addItem(r, "KS Organic Eggs 2 dz",  1, "8.49");
        addItem(r, "KS Bounty Paper Towels", 1, "22.99");
        addItem(r, "Rotisserie Chicken",     1, "4.99");
        addItem(r, "KS Olive Oil 2 L",       1, "14.99");
        addItem(r, "Tillamook Cheese 2 lb",  1, "8.49");
        addItem(r, "KS Purified Water 40pk", 1, "3.99");
        addItem(r, "KS Raw Almonds 3 lb",    1, "8.49");

        receiptRepository.save(r);
    }

    private void seedGasStation() {
        Receipt r = new Receipt();
        r.setStoreName("Costco Gas");
        r.setStoreType(StoreType.GAS_STATION);
        r.setPurchaseDateTime(LocalDateTime.now().minusDays(7).withHour(8).withMinute(40));
        r.setCardType("VISA");
        r.setCardBank("CITI");
        r.setLastFourDigits("7261");
        r.setPaymentCard("CITI_VISA_7261");
        r.setSubtotal(new BigDecimal("52.15"));
        r.setTax(new BigDecimal("0.00"));
        r.setTotal(new BigDecimal("52.15"));

        addItem(r, "Unleaded Regular — 14.9 gal @ $3.499", 1, "52.15");

        receiptRepository.save(r);
    }

    private void seedRestaurant() {
        Receipt r = new Receipt();
        r.setStoreName("Chipotle Mexican Grill");
        r.setStoreType(StoreType.RESTAURANT);
        r.setPurchaseDateTime(LocalDateTime.now().minusDays(5).withHour(12).withMinute(10));
        r.setCardType("MASTERCARD");
        r.setCardBank("CAPITAL_ONE");
        r.setLastFourDigits("4892");
        r.setPaymentCard("CAPITAL_ONE_MASTERCARD_4892");
        r.setSubtotal(new BigDecimal("23.50"));
        r.setTax(new BigDecimal("2.12"));
        r.setTip(new BigDecimal("4.00"));
        r.setTotal(new BigDecimal("29.62"));

        addItem(r, "Burrito Bowl — Chicken",   1, "9.85");
        addItem(r, "Burrito — Steak",          1, "10.65");
        addItem(r, "Chips & Guacamole",        1, "3.00");

        receiptRepository.save(r);
    }

    private void seedGrocery() {
        Receipt r = new Receipt();
        r.setStoreName("Whole Foods Market");
        r.setStoreType(StoreType.GROCERY);
        r.setPurchaseDateTime(LocalDateTime.now().minusDays(2).withHour(18).withMinute(55));
        r.setCardType("VISA");
        r.setCardBank("AMEX");
        r.setLastFourDigits("3301");
        r.setPaymentCard("AMEX_VISA_3301");
        r.setSubtotal(new BigDecimal("63.50"));
        r.setTax(new BigDecimal("3.82"));
        r.setTotal(new BigDecimal("67.32"));

        addItem(r, "Organic Strawberries 1 lb", 1, "5.99");
        addItem(r, "Organic Baby Spinach 5 oz", 1, "4.49");
        addItem(r, "Wild Salmon Fillet 1 lb",   1, "14.99");
        addItem(r, "Avocados (4 ct)",           1, "5.99");
        addItem(r, "365 Whole Milk 1 gal",      1, "5.99");
        addItem(r, "Organic Chicken Breast 2 lb", 1, "12.99");
        addItem(r, "Brown Rice 2 lb",           1, "4.49");
        addItem(r, "Sourdough Bread",           1, "5.49");
        addItem(r, "Almond Milk 0.5 gal",       1, "3.08");

        receiptRepository.save(r);
    }

    private void addItem(Receipt receipt, String name, int qty, String price) {
        ReceiptItem item = new ReceiptItem();
        item.setName(name);
        item.setQuantity(qty);
        item.setUnitPrice(new BigDecimal(price));
        item.setTotalPrice(new BigDecimal(price).multiply(BigDecimal.valueOf(qty)));
        item.setReceipt(receipt);
        receipt.getItems().add(item);
    }
}
