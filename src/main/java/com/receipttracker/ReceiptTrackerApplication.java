package com.receipttracker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ReceiptTrackerApplication {
    public static void main(String[] args) {
        String url  = System.getenv("PROD_DB_URL");
        String user = System.getenv("PROD_DB_USER");
        String pass = System.getenv("PROD_DB_PASSWORD");
        System.out.println("=== DB DEBUG ===");
        System.out.println("PROD_DB_URL      = " + url);
        System.out.println("PROD_DB_USER     = " + user);
        System.out.println("PROD_DB_PASSWORD = " + pass);
        System.out.println("Full connection  = DriverManager.getConnection(\"" + url + "\", \"" + user + "\", \"" + pass + "\")");
        System.out.println("================");
        SpringApplication.run(ReceiptTrackerApplication.class, args);
    }
}
