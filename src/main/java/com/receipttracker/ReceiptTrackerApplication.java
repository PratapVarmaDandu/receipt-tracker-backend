package com.receipttracker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ReceiptTrackerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReceiptTrackerApplication.class, args);
    }
}
