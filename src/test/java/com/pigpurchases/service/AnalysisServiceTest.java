package com.pigpurchases.service;

import com.pigpurchases.TestPdfs;
import com.pigpurchases.model.AnalysisRun;
import com.pigpurchases.model.AppSettings;
import com.pigpurchases.model.BudgetEntry;
import com.pigpurchases.model.StatementSource;
import com.pigpurchases.model.TransactionMapping;
import com.pigpurchases.repository.AnalysisRunRepository;
import com.pigpurchases.repository.AnalysisRunSourceRepository;
import com.pigpurchases.repository.AppSettingsRepository;
import com.pigpurchases.repository.BudgetEntryRepository;
import com.pigpurchases.repository.MerchantCategoryRepository;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Budget-vs-actual math over a mapped run. Uses the generated Crestline statement
 * (coffee 4.10, a 1,230 transfer excluded by rules, metro 0.35, a -150 card
 * payment) and models the real workflow: map, then exclude the card payment.
 */
@SpringBootTest
@ActiveProfiles("test")
class AnalysisServiceTest {

    @Autowired private AnalysisService analysisService;
    @Autowired private MappingService mappingService;
    @Autowired private IngestService ingestService;
    @Autowired private StatementSourceRepository sourceRepo;
    @Autowired private StatementImportRepository importRepo;
    @Autowired private TransactionRepository txnRepo;
    @Autowired private TransactionMappingRepository mappingRepo;
    @Autowired private AnalysisRunRepository runRepo;
    @Autowired private AnalysisRunSourceRepository runSourceRepo;
    @Autowired private BudgetEntryRepository entryRepo;
    @Autowired private MerchantCategoryRepository merchantRepo;
    @Autowired private AppSettingsRepository settingsRepo;

    private Long runId;

    @BeforeEach
    void setUp(@TempDir Path dir) throws IOException {
        mappingRepo.deleteAll();
        merchantRepo.deleteAll();
        runSourceRepo.deleteAll();
        runRepo.deleteAll();
        txnRepo.deleteAll();
        importRepo.deleteAll();
        sourceRepo.deleteAll();
        entryRepo.deleteAll();

        // Monthly allowance = annual / 12 = 100.00 is the top-line budget.
        settingsRepo.deleteAll();
        AppSettings settings = new AppSettings();
        settings.setId(1L);
        settings.setAnnualBudget(new BigDecimal("1200.00"));
        settingsRepo.save(settings);

        entryRepo.save(new BudgetEntry("Coffee Shop", new BigDecimal("50.00")));
        entryRepo.save(new BudgetEntry("Metro Station", new BigDecimal("30.00")));

        StatementSource source = new StatementSource("Crestline Test", dir.toString());
        source.setParserRules("{\"parser\":\"card-pdf\","
                + "\"excludeFromSpend\":[{\"contains\":\"BIG PURCHASE\",\"reason\":\"transfer\"}]}");
        source = sourceRepo.save(source);

        Path pdf = dir.resolve("june.pdf");
        TestPdfs.write(pdf, TestPdfs.CHASE_LINES);
        Long importId = ingestService.ingest(source, pdf).importId();

        AnalysisRun run = mappingService.createRun("2026-06",
                List.of(new MappingService.SourceSelection(source.getId(), importId)), false);
        mappingService.map(run.getId());
        runId = run.getId();

        // Model the review step: exclude the card payment so it isn't counted.
        TransactionMapping payment = mappingRepo
                .findByAnalysisRunIdAndStatus(runId, TransactionMapping.Status.PARKED).get(0);
        mappingService.exclude(runId, payment.getTransactionId());
    }

    @Test
    void monthSummaryComputesBudgetVsActualPerCategoryAndTotal() {
        AnalysisService.MonthSummary ms = analysisService.month("2026-06");

        assertEquals(0, new BigDecimal("100.00").compareTo(ms.totalBudget()), "monthly allowance (1200/12)");
        assertEquals(0, new BigDecimal("4.45").compareTo(ms.totalActual()), "coffee 4.10 + metro 0.35");
        assertEquals(0, new BigDecimal("95.55").compareTo(ms.variance()));
        assertEquals(0, new BigDecimal("1380.00").compareTo(ms.excluded()), "1230 transfer + 150 payment");

        AnalysisService.CategoryRow coffee = ms.categories().stream()
                .filter(c -> "Coffee Shop".equals(c.name())).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("50.00").compareTo(coffee.budget()));
        assertEquals(0, new BigDecimal("4.10").compareTo(coffee.actual()));
        assertEquals(0, new BigDecimal("45.90").compareTo(coffee.variance()));

        // Nothing parked, but "Other" carries the discretionary remainder as its budget:
        // 100 allowance - 80 allocated = 20, with 0 actual.
        AnalysisService.CategoryRow other = ms.categories().stream()
                .filter(c -> c.entryId() == null).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("20.00").compareTo(other.budget()), "discretionary = 100 - 80");
        assertEquals(0, BigDecimal.ZERO.compareTo(other.actual()));
    }

    @Test
    void rollingOverOneMonthEqualsThatMonth() {
        AnalysisService.RollingSummary r = analysisService.rolling();
        assertEquals(1, r.months());
        assertEquals(0, new BigDecimal("4.45").compareTo(r.avgActual()));
        assertEquals(0, new BigDecimal("100.00").compareTo(r.totalBudget()));
    }

    @Test
    void trendsHasOnePointForTheMappedMonth() {
        List<AnalysisService.TrendPoint> trends = analysisService.trends();
        assertEquals(1, trends.size());
        assertEquals("2026-06", trends.get(0).month());
        assertEquals(0, new BigDecimal("4.45").compareTo(trends.get(0).totalActual()));
    }

    @Test
    void mappedMonthsListsTheRun() {
        assertEquals(List.of("2026-06"), analysisService.mappedMonths());
    }

    @Test
    void categoryTransactionsListsTheItemsBehindACategory() {
        Long coffeeId = entryRepo.findAll().stream()
                .filter(e -> "Coffee Shop".equals(e.getName())).findFirst().orElseThrow().getId();

        List<AnalysisService.TxnLine> coffee = analysisService.categoryTransactions("2026-06", coffeeId.toString());
        assertEquals(1, coffee.size());
        assertTrue(coffee.get(0).description().contains("COFFEE SHOP"));
        assertEquals(0, new BigDecimal("4.10").compareTo(coffee.get(0).amount()));
        assertNotNull(coffee.get(0).transactionId(), "id is needed to reassign the row");

        // Payment was excluded, so "other" (parked) is empty.
        assertEquals(0, analysisService.categoryTransactions("2026-06", "other").size());
    }
}
