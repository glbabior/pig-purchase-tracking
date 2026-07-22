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

    private Long sourceId;
    private Long juneImportId;
    private Long mayImportId;

    @BeforeEach
    void reset(@TempDir Path dir) throws IOException {
        mappingRepo.deleteAll();
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
