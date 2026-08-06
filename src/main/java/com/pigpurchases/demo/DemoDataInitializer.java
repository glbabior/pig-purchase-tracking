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

    /** The seeded source's name, and the key that says whether it has been seeded already. */
    private static final String SOURCE_NAME = "Northwind Checking (demo)";

    /**
     * Seed anything that is missing, and only that.
     *
     * <p>Each of the three seeded things is guarded on <b>its own</b> existence. One shared
     * guard on "are there any budget entries" was wrong in a way that produced wrong money:
     * deleting the six seeded categories to start entering your own — the obvious first
     * thing to try — made the count zero again, so the next start seeded a <i>second</i>
     * statement source over the same folder. Ingest is idempotent per source and statement
     * date, so the same PDF then loaded twice, and a run covering both sources counted every
     * transaction twice. The demo's month doubled, in the one place a newcomer is being
     * shown how the numbers are computed.
     */
    @Override
    public void run(ApplicationArguments args) throws Exception {
        Path dir = Path.of(demoDir);
        writeStatements(dir);

        int seededEntries = 0;
        if (budgetEntryRepository.count() == 0) {
            for (String[] e : ENTRIES) {
                BudgetEntry entry = new BudgetEntry(e[0], new BigDecimal(e[1]));
                entry.setQuantity(1);
                entry.setHints(e[2]);
                budgetEntryRepository.save(entry);
            }
            seededEntries = ENTRIES.size();
        }

        // By name rather than by count: a demo where the user added a second source of their
        // own should still not gain a duplicate Northwind one.
        boolean sourceExists = sourceRepository.findAll().stream()
                .anyMatch(s -> SOURCE_NAME.equals(s.getName()));
        if (!sourceExists) {
            StatementSource source = new StatementSource(SOURCE_NAME, dir.toString());
            source.setParserRules("{\"parser\":\"northwind-demo-pdf\"}");
            sourceRepository.save(source);
        }

        // Only when unset. Re-stamping 12000 on every start silently reverted an annual
        // budget the user had changed while looking around, which moves every variance on
        // the Spend screens.
        AppSettings settings = appSettingsRepository.findById(1L).orElseGet(AppSettings::new);
        if (settings.getAnnualBudget() == null || settings.getAnnualBudget().signum() == 0) {
            settings.setAnnualBudget(new BigDecimal("12000.00"));
            appSettingsRepository.save(settings);
        }

        if (seededEntries == 0 && sourceExists) {
            log.info("Demo data already present; leaving it alone. Statements are in {}", dir);
        } else {
            log.info("Demo ready: {} budget entries seeded, statement source {}, statements in {}."
                    + " Open http://localhost:8080, go to Ingest, and load one.",
                    seededEntries, sourceExists ? "already present" : "seeded", dir);
        }
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
