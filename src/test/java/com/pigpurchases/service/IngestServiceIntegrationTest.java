package com.pigpurchases.service;

import com.pigpurchases.TestPdfs;
import com.pigpurchases.model.StatementImport;
import com.pigpurchases.model.StatementSource;
import com.pigpurchases.model.Transaction;
import com.pigpurchases.repository.StatementImportRepository;
import com.pigpurchases.repository.StatementSourceRepository;
import com.pigpurchases.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end ingest test that generates its own PDF (no real statements), so it
 * runs anywhere including CI. Covers parser dispatch by rules, storage, uniform
 * exclusion application, the import record, and idempotent re-ingest.
 */
@SpringBootTest
@ActiveProfiles("test")
class IngestServiceIntegrationTest {

    @Autowired private IngestService ingestService;
    @Autowired private StatementSourceRepository sourceRepo;
    @Autowired private TransactionRepository txnRepo;
    @Autowired private StatementImportRepository importRepo;

    @Test
    void ingestStoresTransactionsAppliesExclusionsAndIsIdempotent(@TempDir Path dir) throws IOException {
        // Put the file in a subfolder so relativePath is exercised.
        Files.createDirectories(dir.resolve("2026"));
        Path pdf = dir.resolve("2026").resolve("crestline-june.pdf");
        TestPdfs.write(pdf, TestPdfs.CHASE_LINES);

        StatementSource source = new StatementSource("Crestline Test", dir.toString());
        source.setParserRules("{\"parser\":\"card-pdf\","
                + "\"excludeFromSpend\":[{\"contains\":\"BIG PURCHASE\",\"reason\":\"test transfer\"}]}");
        source = sourceRepo.save(source);

        IngestService.IngestResult result = ingestService.ingest(source, pdf);
        assertEquals(LocalDate.of(2026, 6, 11), result.statementDate());
        assertEquals(4, result.transactionCount());
        assertEquals(1, result.excludedCount());

        List<Transaction> stored = txnRepo.findByStatementSourceId(source.getId());
        assertEquals(4, stored.size());
        assertTrue(stored.stream().allMatch(t -> "2026-06".equals(t.getMonth())));
        List<Transaction> excluded = stored.stream().filter(Transaction::isExcludeFromSpend).toList();
        assertEquals(1, excluded.size());
        assertTrue(excluded.get(0).getDescription().contains("BIG PURCHASE"));

        List<StatementImport> imports = importRepo.findByStatementSourceIdOrderByStatementDateDesc(source.getId());
        assertEquals(1, imports.size());
        assertEquals(4, imports.get(0).getTransactionCount());
        // relativePath locates the exact file under the source folder.
        String rel = imports.get(0).getRelativePath();
        assertTrue(rel.contains("crestline-june.pdf") && rel.contains("2026"), "relativePath: " + rel);

        // Re-ingest the same file: replaces, does not duplicate.
        ingestService.ingest(source, pdf);
        assertEquals(4, txnRepo.findByStatementSourceId(source.getId()).size());
        assertEquals(1, importRepo.findByStatementSourceIdOrderByStatementDateDesc(source.getId()).size());
    }

    /**
     * A parse that does not tie back to the statement's own printed totals must not be
     * stored. The checks existed only in *ValidationTest, which skips itself away without
     * the personal PDF folder — so in the running app a dropped or mis-signed line became
     * a quietly wrong month, reported as "Loaded — Transactions: N".
     */
    @Test
    void anImportThatDoesNotReconcileIsRefused(@TempDir Path dir) throws IOException {
        Path pdf = dir.resolve("crestline-bad.pdf");
        TestPdfs.write(pdf, List.of(
                "Opening/Closing Date 05/12/26 - 06/11/26",
                "Purchases +$99.99",                       // what the statement prints
                "05/20 COFFEE SHOP ANYTOWN CA 4.10"));     // what the parser found

        StatementSource source = sourceRepo.save(withRules("Crestline Mismatch", dir));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ingestService.ingest(source, pdf));
        assertTrue(e.getMessage().contains("does not reconcile"), e.getMessage());
        assertTrue(txnRepo.findByStatementSourceId(source.getId()).isEmpty(),
                "nothing may be stored from a statement that does not reconcile");
    }

    /**
     * Crestline prints fees and interest as ordinary dated rows in the activity table but
     * totals them on their own summary lines, separate from Purchases. Reconciling the
     * parsed positives against Purchases alone therefore refused every cycle carrying an
     * annual fee, a late fee or an interest charge — a statement the user then could not
     * load at all, blamed on a parser problem that did not exist.
     */
    @Test
    void aStatementCarryingFeesAndInterestStillReconciles(@TempDir Path dir) throws IOException {
        Path pdf = dir.resolve("crestline-fees.pdf");
        TestPdfs.write(pdf, List.of(
                "Opening/Closing Date 05/12/26 - 06/11/26",
                "Purchases +$4.10",
                "Fees Charged +$95.00",
                "Interest Charged +$12.34",
                "05/20 COFFEE SHOP ANYTOWN CA 4.10",
                "05/21 ANNUAL MEMBERSHIP FEE 95.00",
                "05/22 PURCHASE INTEREST CHARGE 12.34"));

        StatementSource source = sourceRepo.save(withRules("Crestline Fees", dir));

        IngestService.IngestResult result = ingestService.ingest(source, pdf);
        assertEquals(3, result.transactionCount(), "the fee and interest rows are real transactions");
        assertEquals(3, txnRepo.findByStatementSourceId(source.getId()).size());
    }

    /**
     * No statement date means no month to group by, and the null used to become the
     * idempotency key — where Spring Data turns it into IS NULL, so a second undated
     * statement matched the first and deleted its transactions.
     */
    @Test
    void anImportWithNoStatementDateIsRefused(@TempDir Path dir) throws IOException {
        Path pdf = dir.resolve("crestline-undated.pdf");
        TestPdfs.write(pdf, List.of("05/20 COFFEE SHOP ANYTOWN CA 4.10"));

        StatementSource source = sourceRepo.save(withRules("Crestline Undated", dir));

        assertThrows(IllegalStateException.class, () -> ingestService.ingest(source, pdf));
        assertTrue(txnRepo.findByStatementSourceId(source.getId()).isEmpty());
    }

    private static StatementSource withRules(String name, Path dir) {
        StatementSource source = new StatementSource(name, dir.toString());
        source.setParserRules("{\"parser\":\"card-pdf\"}");
        return source;
    }
}
