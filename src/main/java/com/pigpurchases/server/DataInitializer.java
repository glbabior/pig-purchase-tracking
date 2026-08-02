package com.pigpurchases.server;

import com.pigpurchases.model.BudgetEntry;
import com.pigpurchases.repository.BudgetEntryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Component
public class DataInitializer implements ApplicationRunner {
    @Autowired
    private BudgetEntryRepository budgetEntryRepository;

    /** Renamed to this once imported, so the migration is a one-time event. */
    private static final String DONE_SUFFIX = ".imported";

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        // One-time migration from the old flat file.
        //
        // The guard used to be "the table is empty", which is not the same question as
        // "have I already migrated". Delete every budget entry on the Items screen and
        // restart, or commit a restore of a backup taken before any entries existed, and
        // 32 entries you did not create reappeared with fresh ids that no mapping links to.
        // Renaming the file records the fact of the migration instead of inferring it.
        //
        // @Transactional so a malformed amount part-way down cannot leave a half-imported
        // budget committed — an ApplicationRunner that throws aborts startup, and the next
        // start would then skip the migration because the table is no longer empty.
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
                    BudgetEntry entry = new BudgetEntry(parts[0], new java.math.BigDecimal(parts[2]));
                    budgetEntryRepository.save(entry);
                }
            }
            // Mark it done so an empty entries table never triggers this again.
            Files.move(dataFile, dataFile.resolveSibling(dataFile.getFileName() + DONE_SUFFIX),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
