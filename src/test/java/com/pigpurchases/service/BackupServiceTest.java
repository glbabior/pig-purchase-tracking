package com.pigpurchases.service;

import com.pigpurchases.model.BudgetEntry;
import com.pigpurchases.model.StatementSource;
import com.pigpurchases.repository.BudgetEntryRepository;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    @BeforeEach
    void clean() throws IOException {
        // Everything the signature reads, not just the two tables these tests write. A test
        // failing earlier in the suite leaves rows behind, and clearing only part of them
        // let an unrelated failure cascade into a confusing failure here.
        mappingRepo.deleteAll();
        txnRepo.deleteAll();
        entryRepo.deleteAll();
        sourceRepo.deleteAll();
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

    /**
     * The change signature read budget_entries as a bare COUNT(*) and statement_sources
     * not at all, so an edit in place left it byte-identical and every backup for the rest
     * of the session was skipped as "no changes" — losing the live file then restored the
     * old allowance and none of the new hints.
     */
    @Test
    void editingABudgetEntryIsNoticedByTheChangeSignature() {
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
        assertNotEquals(afterFirst, lastBackupAt(),
                "editing an entry's allowance and hints must trigger a backup");
    }

    /** The same blindness covered statement sources, which were not in the signature at all. */
    @Test
    void repointingAStatementSourceIsNoticedByTheChangeSignature() {
        StatementSource source = sourceRepo.save(new StatementSource("Crestline", "C:/statements/crestline"));
        backupService.backupNow();
        Object afterFirst = lastBackupAt();

        backupService.scheduled();
        assertEquals(afterFirst, lastBackupAt(), "an unchanged database should not be re-dumped");

        source.setFolderPath("D:/moved/crestline");
        sourceRepo.save(source);

        backupService.scheduled();
        assertNotEquals(afterFirst, lastBackupAt(),
                "repointing a source's folder must trigger a backup");
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
