package com.pigpurchases.service;

import com.pigpurchases.TestPdfs;
import com.pigpurchases.model.AnalysisRun;
import com.pigpurchases.model.BudgetEntry;
import com.pigpurchases.model.StatementSource;
import com.pigpurchases.model.TransactionMapping;
import com.pigpurchases.repository.AnalysisRunRepository;
import com.pigpurchases.repository.AnalysisRunSourceRepository;
import com.pigpurchases.repository.BudgetEntryRepository;
import com.pigpurchases.repository.StatementImportRepository;
import com.pigpurchases.repository.StatementSourceRepository;
import com.pigpurchases.repository.TransactionMappingRepository;
import com.pigpurchases.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mapping runs end to end against a generated statement, so this runs in CI.
 * Covers the rules that make a run trustworthy: every source must contribute,
 * a statement can't be silently reused, excluded transfers never count, and
 * re-running replaces rather than duplicates.
 */
@SpringBootTest
@ActiveProfiles("test")
class MappingServiceTest {

    @Autowired private MappingService mappingService;
    @Autowired private IngestService ingestService;
    @Autowired private StatementSourceRepository sourceRepo;
    @Autowired private StatementImportRepository importRepo;
    @Autowired private TransactionRepository txnRepo;
    @Autowired private TransactionMappingRepository mappingRepo;
    @Autowired private AnalysisRunRepository runRepo;
    @Autowired private AnalysisRunSourceRepository runSourceRepo;
    @Autowired private BudgetEntryRepository entryRepo;
    @Autowired private com.pigpurchases.repository.MerchantCategoryRepository merchantRepo;

    private Long sourceId;
    private Long juneImportId;
    private Long mayImportId;
    private Path tempDir;

    @BeforeEach
    void reset(@TempDir Path dir) throws IOException {
        tempDir = dir;
        mappingRepo.deleteAll();
        merchantRepo.deleteAll();
        runSourceRepo.deleteAll();
        runRepo.deleteAll();
        txnRepo.deleteAll();
        importRepo.deleteAll();
        sourceRepo.deleteAll();
        entryRepo.deleteAll();

        BudgetEntry coffee = new BudgetEntry("Coffee Shop", new BigDecimal("50.00"));
        entryRepo.save(coffee);
        BudgetEntry metro = new BudgetEntry("Metro Station", new BigDecimal("30.00"));
        entryRepo.save(metro);

        StatementSource source = new StatementSource("Crestline Test", dir.toString());
        source.setParserRules("{\"parser\":\"card-pdf\","
                + "\"excludeFromSpend\":[{\"contains\":\"BIG PURCHASE\",\"reason\":\"transfer\"}]}");
        source = sourceRepo.save(source);
        sourceId = source.getId();

        Path june = dir.resolve("june.pdf");
        TestPdfs.write(june, TestPdfs.CHASE_LINES);
        juneImportId = ingestService.ingest(source, june).importId();

        // A second statement for the same source, so "consumed" has something to hide.
        Path may = dir.resolve("may.pdf");
        TestPdfs.write(may, List.of(
                "Opening/Closing Date 04/12/26 - 05/11/26",
                "Purchases +$4.10",
                "04/22 COFFEE SHOP ANYTOWN CA 4.10"));
        mayImportId = ingestService.ingest(source, may).importId();
    }

    private List<MappingService.SourceSelection> select(Long importId) {
        return List.of(new MappingService.SourceSelection(sourceId, importId));
    }

    private Long runFor(Long importId) {
        return runSourceRepo.findByStatementImportId(importId).get(0).getAnalysisRunId();
    }

    /**
     * Pass 2 used to skip everything that was not PARKED, so a remembered decision about
     * a merchant the hint pass could match was written and never read back — the next
     * re-map silently put the transaction back where the hint said, and the month's total
     * moved with it. "COFFEE SHOP ANYTOWN CA" name-matches the "Coffee Shop" entry, so it
     * takes the hint path.
     */
    @Test
    void anExclusionOfAHintMatchedTransactionSurvivesAReMap() {
        mappingService.mapUnmapped();
        Long runId = runFor(mayImportId);
        TransactionMapping hinted = mappingRepo
                .findByAnalysisRunIdAndStatus(runId, TransactionMapping.Status.MAPPED_HINT).get(0);
        Long txnId = hinted.getTransactionId();

        mappingService.exclude(runId, txnId);
        mappingService.remapImports(List.of(mayImportId));

        assertEquals(TransactionMapping.Status.EXCLUDED,
                mappingRepo.findByAnalysisRunIdAndTransactionId(runId, txnId).orElseThrow().getStatus(),
                "a remembered exclusion must outrank the hint that would otherwise re-map it");
    }

    /** The same rule for a re-categorization, which is the commoner case. */
    @Test
    void aManualRecategorizationOfAHintMatchedTransactionSurvivesAReMap() {
        mappingService.mapUnmapped();
        Long runId = runFor(mayImportId);
        TransactionMapping hinted = mappingRepo
                .findByAnalysisRunIdAndStatus(runId, TransactionMapping.Status.MAPPED_HINT).get(0);
        Long txnId = hinted.getTransactionId();
        Long metroId = entryRepo.findAll().stream()
                .filter(e -> "Metro Station".equals(e.getName())).findFirst().orElseThrow().getId();

        mappingService.assign(runId, txnId, metroId);
        mappingService.remapImports(List.of(mayImportId));

        TransactionMapping after = mappingRepo.findByAnalysisRunIdAndTransactionId(runId, txnId).orElseThrow();
        assertEquals(metroId, after.getBudgetEntryId(), "the hint must not reclaim a hand-categorized row");
        assertEquals(TransactionMapping.Status.MAPPED_MANUAL, after.getStatus());
    }

    /**
     * A one-off exclusion writes no rule, so reversing one must delete none. Un-categorizing
     * used to drop the merchant_categories row unconditionally, which forgot that a merchant
     * meant a category — everywhere, for every month — because of a single transaction the
     * user set aside and then changed their mind about.
     */
    @Test
    void reversingAOneOffExclusionLeavesTheMerchantRuleAlone() {
        mappingService.mapUnmapped();
        Long runId = runFor(mayImportId);
        Long txnId = mappingRepo.findByAnalysisRunId(runId).get(0).getTransactionId();
        Long metroId = entryRepo.findAll().stream()
                .filter(e -> "Metro Station".equals(e.getName())).findFirst().orElseThrow().getId();

        mappingService.assign(runId, txnId, metroId);   // teaches the merchant rule
        long rulesAfterTeaching = merchantRepo.count();
        assertTrue(rulesAfterTeaching > 0, "assigning by hand should remember the merchant");

        mappingService.excludeOnce(runId, txnId);       // "not spend, just this one"
        mappingService.assign(runId, txnId, null);      // ...never mind

        assertEquals(rulesAfterTeaching, merchantRepo.count(),
                "reversing a one-off must not forget an unrelated remembered answer");
    }

    /**
     * Re-ingest deletes the statement's transactions and creates new ones with new ids.
     * The mappings used to be left pointing at the deleted rows: the statement then
     * contributed nothing to any month, its run vanished from the Mapping screen, and
     * EXCLUDED_ONCE — which has no merchant rule to be rebuilt from — was gone for good.
     * The in-app guide tells the user re-loading is safe.
     */
    @Test
    void reIngestingAStatementCarriesItsOneOffExclusionOntoTheNewRows() throws IOException {
        mappingService.mapUnmapped();
        Long runId = runFor(mayImportId);
        Long oldTxnId = mappingRepo.findByAnalysisRunId(runId).get(0).getTransactionId();
        mappingService.excludeOnce(runId, oldTxnId);

        StatementSource source = sourceRepo.findById(sourceId).orElseThrow();
        Long newImportId = ingestService.ingest(source, tempDir.resolve("may.pdf")).importId();

        assertEquals(runId, runFor(newImportId), "the run must follow the replacement import");
        List<TransactionMapping> after = mappingRepo.findByAnalysisRunId(runId);
        assertEquals(1, after.size(), "exactly one mapping, on the new row");
        assertNotEquals(oldTxnId, after.get(0).getTransactionId(), "the transaction really was replaced");
        assertEquals(TransactionMapping.Status.EXCLUDED_ONCE, after.get(0).getStatus(),
                "a one-off exclusion cannot be rebuilt from any rule, so re-ingest must carry it");
    }

    @Test
    void mapUnmappedMapsEveryIngestedStatementPerFile() {
        assertEquals(2, mappingService.unmappedImports().size());

        MappingService.MapBatchResult r = mappingService.mapUnmapped();
        assertEquals(2, r.files(), "both statements mapped");
        assertTrue(mappingService.unmappedImports().isEmpty(), "nothing left unmapped");
        // Each statement gets its own run.
        assertTrue(mappingService.consumingRun(juneImportId).isPresent());
        assertTrue(mappingService.consumingRun(mayImportId).isPresent());
        assertFalse(mappingService.consumingRun(juneImportId).get().getId()
                .equals(mappingService.consumingRun(mayImportId).get().getId()), "distinct per-file runs");

        // Re-running maps only the chosen file.
        assertEquals(1, mappingService.remapImports(List.of(juneImportId)).files());
        assertTrue(mappingService.unmappedImports().isEmpty(), "re-map doesn't leave anything unmapped");
    }

    @Test
    void mapsByEntryNameParksTheRestAndNeverCountsExcludedTransfers() {
        AnalysisRun run = mappingService.createRun("2026-06", select(juneImportId), false);
        MappingService.MapResult result = mappingService.map(run.getId());

        // The generated statement has 4 transactions: coffee, big purchase (excluded),
        // metro, and a card payment.
        assertEquals(2, result.mapped(), "COFFEE SHOP and METRO STATION match entry names");
        assertEquals(1, result.excluded(), "BIG PURCHASE is carved out by the source's rules");
        assertEquals(1, result.parked(), "the card payment matches nothing");

        List<TransactionMapping> excluded =
                mappingRepo.findByAnalysisRunIdAndStatus(run.getId(), TransactionMapping.Status.EXCLUDED);
        assertEquals(1, excluded.size());
        assertTrue(excluded.stream().noneMatch(TransactionMapping::countsAsSpend),
                "excluded transfers must never count toward spend");

        List<TransactionMapping> parked =
                mappingRepo.findByAnalysisRunIdAndStatus(run.getId(), TransactionMapping.Status.PARKED);
        assertEquals(1, parked.size());
        assertTrue(parked.get(0).countsAsSpend(), "parked money is real spend, just unattributed");
    }

    @Test
    void aManualCorrectionIsRememberedAndReappliedOnReRunWithoutAi() {
        // AI is disabled in tests, so the cache is the ONLY thing that can promote
        // the parked payment on the second run — which is exactly what we're testing.
        AnalysisRun run = mappingService.createRun("2026-06", select(juneImportId), false);
        mappingService.map(run.getId());

        TransactionMapping parked = mappingRepo
                .findByAnalysisRunIdAndStatus(run.getId(), TransactionMapping.Status.PARKED).get(0);
        Long coffeeId = entryRepo.findAll().stream()
                .filter(e -> e.getName().equals("Coffee Shop")).findFirst().orElseThrow().getId();

        // Correct it by hand — this should teach the merchant cache.
        mappingService.assign(run.getId(), parked.getTransactionId(), coffeeId);
        assertEquals(1, merchantRepo.count(), "the manual correction should be remembered");

        // Re-run: the same merchant now resolves from the cache, no AI call, nothing parked.
        MappingService.MapResult result = mappingService.map(run.getId());
        assertEquals(0, result.parked());
        assertEquals(1, result.cached(), "the payment is remembered from the manual correction");
        assertEquals(0, result.aiMapped(), "no live API call — the answer was cached");
        assertEquals(3, result.mapped());

        TransactionMapping reapplied = mappingRepo
                .findByAnalysisRunIdAndTransactionId(run.getId(), parked.getTransactionId()).orElseThrow();
        assertEquals(TransactionMapping.Status.MAPPED_MANUAL, reapplied.getStatus());
        assertEquals(coffeeId, reapplied.getBudgetEntryId());
    }

    @Test
    void excludingByHandIsRememberedAndReappliedOnReRun() {
        AnalysisRun run = mappingService.createRun("2026-06", select(juneImportId), false);
        mappingService.map(run.getId());

        TransactionMapping parked = mappingRepo
                .findByAnalysisRunIdAndStatus(run.getId(), TransactionMapping.Status.PARKED).get(0);

        // Mark the parked card-payment-style row as not-spend.
        mappingService.exclude(run.getId(), parked.getTransactionId());

        TransactionMapping after = mappingRepo
                .findByAnalysisRunIdAndTransactionId(run.getId(), parked.getTransactionId()).orElseThrow();
        assertEquals(TransactionMapping.Status.EXCLUDED, after.getStatus());
        assertFalse(after.countsAsSpend(), "excluded money must not count as spend");
        assertEquals(1, merchantRepo.count(), "the exclusion should be remembered");

        // Re-run: the same merchant auto-excludes, no longer parked, without an AI call.
        MappingService.MapResult result = mappingService.map(run.getId());
        assertEquals(0, result.parked());
        assertEquals(0, result.cached(), "an exclusion is not a categorization");
        assertEquals(2, result.excluded(), "the parser transfer plus the remembered exclusion");
        TransactionMapping reapplied = mappingRepo
                .findByAnalysisRunIdAndTransactionId(run.getId(), parked.getTransactionId()).orElseThrow();
        assertEquals(TransactionMapping.Status.EXCLUDED, reapplied.getStatus());
    }

    @Test
    void excludingJustThisOneCreatesNoRuleButSurvivesAReRun() {
        AnalysisRun run = mappingService.createRun("2026-06", select(juneImportId), false);
        mappingService.map(run.getId());

        TransactionMapping parked = mappingRepo
                .findByAnalysisRunIdAndStatus(run.getId(), TransactionMapping.Status.PARKED).get(0);
        long merchantsBefore = merchantRepo.count();

        mappingService.excludeOnce(run.getId(), parked.getTransactionId());

        TransactionMapping after = mappingRepo
                .findByAnalysisRunIdAndTransactionId(run.getId(), parked.getTransactionId()).orElseThrow();
        assertEquals(TransactionMapping.Status.EXCLUDED_ONCE, after.getStatus());
        assertFalse(after.countsAsSpend(), "a one-off exclusion must not count as spend");
        assertEquals(merchantsBefore, merchantRepo.count(),
                "a one-off exclusion must not teach the merchant cache anything");

        // Re-running the statement must not quietly turn it back into spend: there is
        // no rule to rebuild it from, so doMap has to carry the decision across.
        MappingService.MapResult result = mappingService.map(run.getId());
        assertEquals(2, result.excluded(), "the parser transfer plus the one-off exclusion");
        TransactionMapping reapplied = mappingRepo
                .findByAnalysisRunIdAndTransactionId(run.getId(), parked.getTransactionId()).orElseThrow();
        assertEquals(TransactionMapping.Status.EXCLUDED_ONCE, reapplied.getStatus());
        assertEquals(merchantsBefore, merchantRepo.count(), "still no rule after a re-run");
    }

    @Test
    void aOneOffExclusionCanBeUndoneByReassigningIt() {
        AnalysisRun run = mappingService.createRun("2026-06", select(juneImportId), false);
        mappingService.map(run.getId());
        TransactionMapping parked = mappingRepo
                .findByAnalysisRunIdAndStatus(run.getId(), TransactionMapping.Status.PARKED).get(0);
        mappingService.excludeOnce(run.getId(), parked.getTransactionId());

        // Putting it back into a category clears the one-off, and the re-run respects that.
        Long coffeeId = entryRepo.findAll().get(0).getId();
        mappingService.assign(run.getId(), parked.getTransactionId(), coffeeId);
        MappingService.MapResult result = mappingService.map(run.getId());

        TransactionMapping reapplied = mappingRepo
                .findByAnalysisRunIdAndTransactionId(run.getId(), parked.getTransactionId()).orElseThrow();
        assertEquals(coffeeId, reapplied.getBudgetEntryId());
        assertEquals(1, result.excluded(), "only the parser transfer remains excluded");
    }

    @Test
    void unCategorizingByHandForgetsTheRememberedAnswer() {
        AnalysisRun run = mappingService.createRun("2026-06", select(juneImportId), false);
        mappingService.map(run.getId());
        TransactionMapping parked = mappingRepo
                .findByAnalysisRunIdAndStatus(run.getId(), TransactionMapping.Status.PARKED).get(0);
        Long coffeeId = entryRepo.findAll().get(0).getId();

        mappingService.assign(run.getId(), parked.getTransactionId(), coffeeId);
        assertEquals(1, merchantRepo.count());

        // Deliberately parking it again means "that was wrong" — the memory is dropped.
        mappingService.assign(run.getId(), parked.getTransactionId(), null);
        assertEquals(0, merchantRepo.count(), "un-categorizing should forget the merchant");

        MappingService.MapResult result = mappingService.map(run.getId());
        assertEquals(1, result.parked(), "with the memory gone, it parks again");
        assertEquals(0, result.cached());
    }

    @Test
    void aRememberedAnswerForADeletedEntryIsPurgedRatherThanApplied() {
        AnalysisRun run = mappingService.createRun("2026-06", select(juneImportId), false);
        mappingService.map(run.getId());
        TransactionMapping parked = mappingRepo
                .findByAnalysisRunIdAndStatus(run.getId(), TransactionMapping.Status.PARKED).get(0);
        Long coffeeId = entryRepo.findAll().stream()
                .filter(e -> e.getName().equals("Coffee Shop")).findFirst().orElseThrow().getId();

        mappingService.assign(run.getId(), parked.getTransactionId(), coffeeId);
        assertEquals(1, merchantRepo.count());

        // The remembered answer now points at an entry that no longer exists.
        entryRepo.deleteById(coffeeId);

        MappingService.MapResult result = mappingService.map(run.getId());
        assertEquals(0, merchantRepo.count(), "a stale remembered answer should be purged, not applied");
        assertEquals(0, result.cached());
        // The payment parks again; the COFFEE SHOP line no longer name-matches either.
        assertTrue(result.parked() >= 1);
    }

    @Test
    void everySourceMustContributeAStatement() {
        // A second source with nothing selected for it.
        sourceRepo.save(new StatementSource("Other Account", "C:/nowhere"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> mappingService.createRun("2026-06", select(juneImportId), false));
        assertTrue(ex.getMessage().contains("Other Account"), ex.getMessage());
    }

    @Test
    void aStatementCannotBeSilentlyCountedIntoTwoMonths() {
        mappingService.createRun("2026-06", select(juneImportId), false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> mappingService.createRun("2026-07", select(juneImportId), false));
        assertTrue(ex.getMessage().contains("already been used"), ex.getMessage());

        // ...but it can be reused when the user explicitly asks.
        AnalysisRun forced = mappingService.createRun("2026-07", select(juneImportId), true);
        assertEquals("2026-07", forced.getMonth());
    }

    @Test
    void consumedStatementsAreHiddenAndReleasedWhenTheRunIsDeleted() {
        AnalysisRun run = mappingService.createRun("2026-06", select(juneImportId), false);
        assertTrue(mappingService.consumedImportIds().contains(juneImportId));
        assertEquals("2026-06", mappingService.consumingRun(juneImportId).orElseThrow().getMonth());
        assertTrue(!mappingService.consumedImportIds().contains(mayImportId), "untouched statement stays available");

        mappingService.deleteRun(run.getId());
        assertTrue(mappingService.consumedImportIds().isEmpty(), "deleting a run releases its statements");
    }

    @Test
    void reRunningReplacesResultsRatherThanDuplicatingThem() {
        AnalysisRun run = mappingService.createRun("2026-06", select(juneImportId), false);
        mappingService.map(run.getId());
        int first = mappingRepo.findByAnalysisRunId(run.getId()).size();

        mappingService.map(run.getId());
        assertEquals(first, mappingRepo.findByAnalysisRunId(run.getId()).size());
        // The ingested transactions themselves are untouched by mapping.
        assertEquals(4, txnRepo.findByStatementImportId(juneImportId).size());
    }

    @Test
    void manualAssignmentMovesATransactionOutOfTheParkedBucket() {
        AnalysisRun run = mappingService.createRun("2026-06", select(juneImportId), false);
        mappingService.map(run.getId());

        TransactionMapping parked = mappingRepo
                .findByAnalysisRunIdAndStatus(run.getId(), TransactionMapping.Status.PARKED).get(0);
        Long entryId = entryRepo.findAll().get(0).getId();

        mappingService.assign(run.getId(), parked.getTransactionId(), entryId);

        AnalysisRun after = runRepo.findById(run.getId()).orElseThrow();
        assertEquals(3, after.getMappedCount());
        assertEquals(0, after.getParkedCount());
        assertEquals(1, after.getExcludedCount());
    }

    @Test
    void aMonthCanOnlyHaveOneRunAndTheMonthMustBeWellFormed() {
        mappingService.createRun("2026-06", select(juneImportId), false);
        assertThrows(IllegalArgumentException.class,
                () -> mappingService.createRun("2026-06", select(mayImportId), false));
        assertThrows(IllegalArgumentException.class,
                () -> mappingService.createRun("June 2026", select(mayImportId), false));
        assertThrows(IllegalArgumentException.class,
                () -> mappingService.createRun("2026-13", select(mayImportId), false));
    }
}
