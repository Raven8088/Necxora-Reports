package com.nexora.reports;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Nexora Reports (R1): executes logical plans authorized by Nexora Semantic as parameterized, read-only PostgreSQL
 * queries and returns tabular JSON results.
 */
@SpringBootApplication
public class ReportsApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReportsApplication.class, args);
    }
}
