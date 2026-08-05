package com.pigpurchases.demo;

import com.pigpurchases.model.AppSettings;
import com.pigpurchases.model.BudgetEntry;
import com.pigpurchases.model.StatementSource;
import com.pigpurchases.repository.AppSettingsRepository;
import com.pigpurchases.repository.BudgetEntryRepository;
import com.pigpurchases.repository.StatementSourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/**
 * Sets up a runnable demonstration: generates sample statements, a statement source
 * pointing at them, and budget entries whose hints will place most of what they contain.
 *
 * <p>Active only under the {@code demo} profile, which also redirects the database and
 * disables backups — see {@code application-demo.properties}. Nothing here can run against
 * a normal launch.
 *
 * <p><b>It stops at ingest.</b> The statements are written and the source is configured,
 * but nothing is imported or mapped, because watching a file become transactions and then
 * become categories is the part worth seeing. Open the Ingest screen and load one.
 *
 * <p>Idempotent: it seeds only when the budget table is empty, so a restart does not
 * duplicate anything and does not overwrite changes made while clicking around.
 */
@Component
@Profile("demo")
public class DemoDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);

    @Autowired private BudgetEntryRepository budgetEntryRepository;
    @Autowired private StatementSourceRepository sourceRepository;
    @Autowired private AppSettingsRepository appSettingsRepository;

    @Value("${pigpurchases.demo.dir}")
    private String demoDir;

    /**
     * Categories and the hints that catch the generated merchants.
     *
     * <p>Deliberately incomplete. Two merchants in every statement — a hardware shop and a
     * florist — match nothing here, so they park as "Other". A demo where everything
     * resolves would hide the review screen, the hint-suggestion prompt and the parked
     * bucket, which are most of what makes the mapping model worth looking at.
     */
    private static final List<String[]> ENTRIES = List.of(
            new String[] {"Groceries",     "450.00", "match: FRESH MARKET"},
            new String[] {"Coffee",         "40.00", "match: DAILY GRIND"},
            new String[] {"Utilities",     "120.00", "match: CITY POWER"},
            new String[] {"Transit",        "60.00", "match: METRO TRANSIT"},
            new String[] {"Dining out",    "150.00", "match: TAQUERIA\nmatch: PIZZA"},
            new String[] {"Subscriptions",  "20.00", "match: STREAMFLIX"});

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Path dir = Path.of(demoDir);
        writeStatements(dir);

        if (budgetEntryRepository.count() > 0) {
            log.info("Demo data already present; leaving it alone. Statements are in {}", dir);
            return;
        }

        for (String[] e : ENTRIES) {
            BudgetEntry entry = new BudgetEntry(e[0], new BigDecimal(e[1]));
            entry.setQuantity(1);
            entry.setHints(e[2]);
            budgetEntryRepository.save(entry);
        }

        StatementSource source = new StatementSource("Northwind Checking (demo)", dir.toString());
        source.setParserRules("{\"parser\":\"northwind-demo-pdf\"}");
        sourceRepository.save(source);

        AppSettings settings = appSettingsRepository.findById(1L).orElseGet(AppSettings::new);
        settings.setAnnualBudget(new BigDecimal("12000.00"));
        appSettingsRepository.save(settings);

        log.info("Demo ready: {} budget entries, 1 statement source, statements in {}."
                + " Open http://localhost:8080, go to Ingest, and load one.",
                ENTRIES.size(), dir);
    }

    /** Three months of statements, rewritten each start so the folder is never half-built. */
    private void writeStatements(Path dir) throws IOException {
        Files.createDirectories(dir);
        LocalDate periodEnd = LocalDate.now().withDayOfMonth(11);
        for (int i = 0; i < 3; i++) {
            LocalDate end = periodEnd.minusMonths(i);
            Path file = dir.resolve("northwind-" + end.getYear() + "-"
                    + String.format("%02d", end.getMonthValue()) + ".pdf");
            DemoStatements.writePdf(file, DemoStatements.monthOf(end));
        }
    }
}
