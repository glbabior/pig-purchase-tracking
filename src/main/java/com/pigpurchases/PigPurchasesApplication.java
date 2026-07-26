package com.pigpurchases;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Application entry point. Lives in the root package (com.pigpurchases) so that
 * Spring Boot's default component scan, JPA entity scan, and repository scan
 * cover every sub-package (model, repository, service, server).
 *
 * {@code @EnableScheduling} powers the periodic database backup (see
 * {@link com.pigpurchases.service.BackupService}).
 */
@SpringBootApplication
@EnableScheduling
public class PigPurchasesApplication {
    public static void main(String[] args) {
        SpringApplication.run(PigPurchasesApplication.class, args);
    }
}
