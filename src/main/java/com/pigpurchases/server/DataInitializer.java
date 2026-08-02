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

    /**
     * One-time migration from the old flat file.
     *
     * <p>The guard was "the table is empty", which is not the same question as "have I
     * already migrated": delete every budget entry on the Items screen and restart, or
     * commit a restore of a backup taken before any entries existed, and 32 entries you
     * did not create reappear with fresh ids that no mapping links to.
     *
     * <p>The two questions are now separated, and the order matters. Importing only when
     * the table is empty is the same condition as before — but renaming happens whenever
     * the file is present, so an installation that migrated long ago (where the table is
     * emphatically not empty) still records the fact and stops being vulnerable. Putting
     * the rename inside the import block, as the first attempt did, left the bug live on
     * exactly the machines that already had it.
     *
     * <p>The rename is also deliberately outside the transaction that does the import: a
     * filesystem move cannot roll back, so marking the migration done before knowing it
     * committed would skip it forever after a commit failure.
     */
    @Override
    public void run(ApplicationArguments args) throws Exception {
        Path dataFile = Path.of("pig-purchases-data.txt");
        if (!Files.exists(dataFile)) {
            return;
        }
        if (budgetEntryRepository.count() == 0) {
            importFrom(dataFile);
        }
        Files.move(dataFile, dataFile.resolveSibling(dataFile.getFileName() + DONE_SUFFIX),
                StandardCopyOption.REPLACE_EXISTING);
    }

    /** @Transactional so a malformed amount part-way down cannot commit half a budget. */
    @Transactional
    protected void importFrom(Path dataFile) throws Exception {
        {
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
        }
    }
}
