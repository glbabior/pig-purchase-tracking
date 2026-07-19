package com.pigpurchases.server;

import com.pigpurchases.model.BudgetEntry;
import com.pigpurchases.repository.BudgetEntryRepository;
import com.pigpurchases.service.BudgetService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class DataInitializer implements ApplicationRunner {
    @Autowired
    private BudgetEntryRepository budgetEntryRepository;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // Migrate data from flat file to database if it exists
        Path dataFile = Path.of("pig-purchases-data.txt");
        if (Files.exists(dataFile) && budgetEntryRepository.count() == 0) {
            String content = Files.readString(dataFile, StandardCharsets.UTF_8);
            String[] lines = content.split("\\R");
            boolean inBudgetSection = false;
            for (String line : lines) {
                if (line.startsWith("budgetEntries=")) {
                    inBudgetSection = true;
                    continue;
                }
                if (line.startsWith("ignoredEntries=") || line.startsWith("purchaseSources=") || line.startsWith("settings=")) {
                    inBudgetSection = false;
                    continue;
                }
                if (!inBudgetSection || line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\\|", 3);
                if (parts.length == 3) {
                    BudgetEntry entry = new BudgetEntry(parts[0], parts[0], new java.math.BigDecimal(parts[2]));
                    budgetEntryRepository.save(entry);
                }
            }
        }
    }
}
