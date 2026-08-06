package com.pigpurchases.service;

import com.pigpurchases.model.BudgetEntry;
import com.pigpurchases.model.DismissedDuplicate;
import com.pigpurchases.model.StatementSource;
import com.pigpurchases.repository.BudgetEntryRepository;
import com.pigpurchases.repository.DismissedDuplicateRepository;
import com.pigpurchases.repository.StatementSourceRepository;
import com.pigpurchases.repository.TransactionMappingRepository;
import com.pigpurchases.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The backup subsystem is the app's insurance policy and had no tests at all. These
 * cover the two properties that make it worth having: it notices that something
 * changed, and it writes a file that is either complete or not there.
 *
 * <p>Backups are disabled in the test profile, so this class turns them back on and
 * points them at a directory under {@code target/}.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "pigpurchases.backup.enabled=true",
        "pigpurchases.backup.dir=target/test-backups"
})
class BackupServiceTest {

    private static final Path DIR = Path.of("target/test-backups");

    @Autowired private BackupService backupService;
    @Autowired private BudgetEntryRepository entryRepo;
    @Autowired private StatementSourceRepository sourceRepo;
    @Autowired private TransactionMappingRepository mappingRepo;
    @Autowired private TransactionRepository txnRepo;
    @Autowired private DismissedDuplicateRepository dismissedRepo;
    @Autowired private DebugLogService debugLogService;

    @BeforeEach
    void clean() throws IOException {
        // Everything the signature reads, not just the two tables these tests write. A test
        // failing earlier in the suite leaves rows behind, and clearing only part of them
        // let an unrelated failure cascade into a confusing failure here.
        mappingRepo.deleteAll();
        txnRepo.deleteAll();
        entryRepo.deleteAll();
        sourceRepo.deleteAll();
        dismissedRepo.deleteAll();
        // BackupService is a singleton, so clear the signature this suite's other tests left
        // behind. This writes a backup of its own, hence deleting the files afterwards.
        backupService.resetBaselineAfterRestore();
        deleteBackupFiles();
    }

    private void deleteBackupFiles() throws IOException {
        if (!Files.isDirectory(DIR)) {
            return;
        }
        // Everything under DIR, directories included. Filtering to regular files let a
        // stray directory — this suite deliberately creates one to make a dump fail —
        // survive into later runs, where it is indistinguishable from a backup file and
        // cannot be read.
        try (Stream<Path> s = Files.walk(DIR)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                if (!p.equals(DIR)) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    /** When the last backup was written, or null. Unchanged across a skipped run. */
    private Object lastBackupAt() {
        return backupService.status().get("lastBackupAt");
    }

    @Test
    void aWrittenBackupIsRecordedInTheDebugLog() {
        // Backups were console-only, and a console nobody is watching is not a record. This
        // is the subsystem whose entire job is to be trustworthy, so "did it run?" has to be
        // answerable from inside the app.
        entryRepo.save(new BudgetEntry("Groceries", new BigDecimal("400.00")));
        backupService.backupNow();

        boolean logged = debugLogService.recent().stream()
                .anyMatch(e -> "backup".equals(e.getCategory())
                        && e.getMessage() != null && e.getMessage().contains("Wrote "));
        assertTrue(logged, "a written backup must appear in the debug log");
    }

    @Test
    void aRoutineSkipIsNotLogged() {
        // The scheduler runs every ten minutes. Logging "nothing changed" would add ~144
        // entries a day and bury the ones worth reading, and Settings already shows the last
        // backup time for answering "is it still running?".
        entryRepo.save(new BudgetEntry("Groceries", new BigDecimal("400.00")));
        backupService.backupNow();
        long after = debugLogService.recent().stream()
                .filter(e -> "backup".equals(e.getCategory())).count();

        // scheduled() is the non-forced path — the one that actually runs every ten minutes.
        // backupNow() forces a write, so it could not show this.
        backupService.scheduled();

        assertEquals(after, debugLogService.recent().stream()
                        .filter(e -> "backup".equals(e.getCategory())).count(),
                "a skipped backup must not add an entry");
    }

    /**
     * The contents of today's dump.
     *
     * <p>Asserting on this rather than on {@code lastBackupAt} because the timestamp is a
     * proxy for the thing that matters and a flaky one: two backups a few milliseconds
     * apart can land on the same {@code LocalDateTime}, so the test intermittently read a
     * successful write as a skip. What the user actually needs is that the edit REACHED a
     * backup, which is exactly what the file says.
     */
    private String dumpContents() throws IOException {
        try (Stream<Path> s = Files.list(DIR)) {
            Path daily = s.filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .findFirst().orElseThrow(() -> new IllegalStateException("no backup written"));
            return Files.readString(daily);
        }
    }

    /**
     * The change signature read budget_entries as a bare COUNT(*) and statement_sources
     * not at all, so an edit in place left it byte-identical and every backup for the rest
     * of the session was skipped as "no changes" — losing the live file then restored the
     * old allowance and none of the new hints.
     */
    @Test
    void editingABudgetEntryIsNoticedByTheChangeSignature() throws IOException {
        BudgetEntry entry = entryRepo.save(new BudgetEntry("Coffee", new BigDecimal("50.00")));
        backupService.backupNow();              // forces a write, recording the signature
        Object afterFirst = lastBackupAt();

        backupService.scheduled();              // nothing changed — must skip
        assertEquals(afterFirst, lastBackupAt(), "an unchanged database should not be re-dumped");

        // An edit in place: no row count moves, which is exactly what used to be invisible.
        entry.setMonthlyAllowance(new BigDecimal("400.00"));
        entry.setHints("match: DAILYGRIND");
        entryRepo.save(entry);

        backupService.scheduled();

        String dump = dumpContents();
        assertTrue(dump.contains("400.00"),
                "the new allowance must have reached a backup, not just the live database");
        assertTrue(dump.contains("DAILYGRIND"), "and so must the new hint");
    }

    /** The same blindness covered statement sources, which were not in the signature at all. */
    @Test
    void repointingAStatementSourceIsNoticedByTheChangeSignature() throws IOException {
        StatementSource source = sourceRepo.save(new StatementSource("Crestline", "C:/statements/crestline"));
        backupService.backupNow();
        Object afterFirst = lastBackupAt();

        backupService.scheduled();
        assertEquals(afterFirst, lastBackupAt(), "an unchanged database should not be re-dumped");

        source.setFolderPath("D:/moved/crestline");
        sourceRepo.save(source);

        backupService.scheduled();
        assertTrue(dumpContents().contains("D:/moved/crestline"),
                "the repointed folder must have reached a backup");
    }

    /**
     * Dismissing a duplicate group was invisible to the fingerprint, so a session spent
     * clearing the Duplicates screen and doing nothing else never reached a backup — and
     * the next startup skipped too, because the sidecar restored the same signature.
     */
    @Test
    void dismissingADuplicateIsNoticedByTheChangeSignature() throws IOException {
        entryRepo.save(new BudgetEntry("Coffee", new BigDecimal("50.00")));
        backupService.backupNow();
        Object afterFirst = lastBackupAt();

        backupService.scheduled();
        assertEquals(afterFirst, lastBackupAt(), "an unchanged database should not be re-dumped");

        dismissedRepo.save(new DismissedDuplicate("2026-06-15|89.99|out"));

        backupService.scheduled();
        assertTrue(dumpContents().contains("2026-06-15|89.99|out"),
                "a dismissed duplicate must reach a backup");
    }

    /**
     * The property that actually changed: <b>a dump that fails leaves the previous good
     * daily file intact.</b> {@code SCRIPT TO} truncates its target and streams into it, so
     * writing straight to the daily file destroyed the good copy before knowing the new one
     * would finish — a kill during the shutdown dump, a full disk, or a scanner lock left a
     * truncated .sql that RUNSCRIPT loads partway before erroring.
     *
     * <p>The failure is induced by putting a <i>directory</i> where the staging file goes,
     * which is the cheapest way to make H2's write fail. Asserting only "no .part survives"
     * would pass against the pre-fix code too, since it never created one — that version of
     * this test was vacuous.
     */
    @Test
    void aFailedDumpLeavesThePreviousGoodBackupIntact() throws IOException {
        entryRepo.save(new BudgetEntry("Coffee", new BigDecimal("50.00")));
        backupService.backupNow();

        Path daily;
        try (Stream<Path> s = Files.list(DIR)) {
            daily = s.filter(p -> p.getFileName().toString().endsWith(".sql")).findFirst().orElseThrow();
        }
        String goodDump = Files.readString(daily);
        assertFalse(goodDump.isBlank(), "the first dump must have content to protect");

        // Block the staging path, then give the signature something to notice.
        Path staging = daily.resolveSibling(daily.getFileName() + ".part");
        Files.createDirectory(staging);
        try {
            sourceRepo.save(new StatementSource("Crestline", "C:/statements/crestline"));
            backupService.backupNow();

            assertEquals(goodDump, Files.readString(daily),
                    "a failed dump must not touch the previous good daily backup");
        } finally {
            // Verified, not best-effort. A directory left at the staging path makes EVERY
            // later backup in this class fail the same way, which surfaced as an unrelated
            // test failing — and Windows can defer a directory delete just long enough for
            // the next create to see a phantom, so this retries and then asserts.
            removeStagingDirectory(staging);
        }
    }

    /**
     * The anti-clobber guard used to switch itself off the moment it was needed.
     *
     * <p>The baseline was seeded from the NEWEST sidecar, so once one backup recorded zero
     * mapped rows, {@code lastGoodRichness} became 0 — and the guard's own
     * {@code lastGoodRichness >= 20} floor is false at 0. From then on every empty backup
     * overwrote the day's real file in silence, and nothing was ever filed
     * {@code .SUSPECT}. It was found on a real machine with two consecutive daily backups
     * holding no mappings and no warning anywhere.
     */
    @Test
    void anEmptyBackupDoesNotDisarmTheGuardForEveryBackupAfterIt() throws IOException {
        writeBackupPair("pigpurchases-2020-01-01.sql", 214, "startup");
        writeBackupPair("pigpurchases-2020-01-02.sql", 0, "startup"); // the poisoned newest

        // The live database here has no mappings, so this run's richness is 0 — a collapse
        // against the 214 that is still in force, and the guard must say so.
        backupService.onReady();

        assertTrue(suspectFileExists(), "a drop to zero must be filed .SUSPECT, not written"
                + " over the daily file, even when the newest sidecar already said zero");
    }

    /**
     * The other half, and the reason the baseline cannot simply be the maximum on disk.
     *
     * <p>Accepting a deliberate drop must survive a restart. Seeding from the highest
     * richness of all time would re-flag an accepted database on every start — the exact
     * bug {@code acceptCurrentAsNormal} was written to end.
     */
    @Test
    void anAcceptedDropStaysAcceptedAcrossARestart() throws IOException {
        writeBackupPair("pigpurchases-2020-01-01.sql", 214, "startup");
        writeBackupPair("pigpurchases-2020-01-02.sql", 0, "baseline-accepted");

        backupService.onReady();

        assertFalse(suspectFileExists(), "a drop the user already accepted must not be"
                + " re-flagged from a sidecar older than the acceptance");
    }

    /** A backup file and its sidecar, as BackupService would have left them. */
    private static void writeBackupPair(String name, int richness, String trigger) throws IOException {
        Files.createDirectories(DIR);
        Files.writeString(DIR.resolve(name), "-- dump placeholder; seeding reads the sidecar\n");
        // A signature that cannot match the live one, so the startup run is never skipped
        // as "nothing changed" before it reaches the guard.
        Files.writeString(DIR.resolve(name + ".meta"),
                "richness=" + richness + "\n"
                        + "at=2020-01-01T00:00:00\n"
                        + "signature=stale-" + name + "\n"
                        + "trigger=" + trigger + "\n");
    }

    private static boolean suspectFileExists() throws IOException {
        try (Stream<Path> s = Files.list(DIR)) {
            return s.anyMatch(p -> p.getFileName().toString().contains(".SUSPECT."));
        }
    }

    private static void removeStagingDirectory(Path staging) throws IOException {
        for (int attempt = 0; attempt < 20 && Files.exists(staging); attempt++) {
            try {
                Files.deleteIfExists(staging);
            } catch (IOException retryable) {
                try {
                    Thread.sleep(25);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        assertFalse(Files.exists(staging),
                "the blocking directory must be gone, or it breaks every later backup: " + staging);
    }

    /** A successful dump leaves no staging file and a sidecar beside every dump. */
    @Test
    void aCompletedBackupLeavesNoStagingFileAndAMatchingSidecar() throws IOException {
        entryRepo.save(new BudgetEntry("Coffee", new BigDecimal("50.00")));
        backupService.backupNow();

        try (Stream<Path> s = Files.list(DIR)) {
            assertTrue(s.noneMatch(p -> p.getFileName().toString().endsWith(".part")),
                    "the staging file must not survive a successful dump");
        }
        try (Stream<Path> s = Files.list(DIR)) {
            for (Path sql : s.filter(p -> p.getFileName().toString().endsWith(".sql")).toList()) {
                Path meta = sql.resolveSibling(sql.getFileName() + ".meta");
                assertTrue(Files.exists(meta), "every dump needs its sidecar: " + sql.getFileName());
                assertFalse(Files.readString(sql).isBlank(), "the dump must not be empty");
            }
        }
    }
}
