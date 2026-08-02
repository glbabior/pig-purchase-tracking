package com.pigpurchases.service;

import com.pigpurchases.TestPdfs;
import com.pigpurchases.model.AnalysisRun;
import com.pigpurchases.model.AppSettings;
import com.pigpurchases.model.BudgetEntry;
import com.pigpurchases.model.StatementSource;
import com.pigpurchases.model.Transaction;
import com.pigpurchases.model.TransactionMapping;
import com.pigpurchases.repository.AnalysisRunRepository;
import com.pigpurchases.repository.AnalysisRunSourceRepository;
import com.pigpurchases.repository.AppSettingsRepository;
import com.pigpurchases.repository.BudgetEntryRepository;
import com.pigpurchases.repository.MerchantCategoryRepository;
import com.pigpurchases.repository.MonthStatusRepository;
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
import java.time.LocalDate;
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
    @Autowired private MonthStatusRepository monthStatusRepo;

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
        monthStatusRepo.deleteAll();
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

    // The statement was mapped as a "2026-06" run, but its transactions are dated
    // in May (05/20-05/24), so by ACTUAL date they belong to 2026-05.
    @Test
    void monthSummaryComputesBudgetVsActualPerCategoryAndTotal() {
        AnalysisService.MonthSummary ms = analysisService.month("2026-05");

        assertEquals(0, new BigDecimal("100.00").compareTo(ms.totalBudget()), "monthly allowance (1200/12)");
        assertEquals(0, new BigDecimal("4.45").compareTo(ms.totalActual()), "coffee 4.10 + metro 0.35");
        assertEquals(0, new BigDecimal("95.55").compareTo(ms.variance()));
        // Net (signed), consistent with the drill-down dialog and spend math:
        // +1230 excluded purchase minus the 150 excluded card payment (money in).
        assertEquals(0, new BigDecimal("1080.00").compareTo(ms.excluded()), "1230 purchase - 150 payment");

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

    /**
     * The Bayside parser has no CREDIT type — every positive line is a DEPOSIT — so keeping all
     * money-in out of the spend buckets left a returned purchase showing as spend, and
     * assigning the refund to its category appeared to work while changing nothing.
     *
     * <p>A hand-assigned money-in row therefore nets against its category. A parked or
     * AI-guessed one still does not, which is what the exclusion was added for.
     */
    @Test
    void aRefundAssignedByHandNetsAgainstItsCategory() {
        Long coffeeId = entryRepo.findAll().stream()
                .filter(e -> "Coffee Shop".equals(e.getName())).findFirst().orElseThrow().getId();

        // A $4.10 refund of the coffee, arriving as Bayside does it: positive, typed DEPOSIT.
        Transaction refund = new Transaction(LocalDate.of(2026, 5, 22), "COFFEE SHOP REFUND",
                "COFFEE SHOP", new BigDecimal("4.10"), "2026-05");
        refund.setType("DEPOSIT");
        txnRepo.save(refund);
        mappingRepo.save(new TransactionMapping(runId, refund.getId(), coffeeId,
                TransactionMapping.Status.MAPPED_MANUAL, "Categorized by hand"));

        AnalysisService.CategoryRow coffee = analysisService.month("2026-05").categories().stream()
                .filter(c -> "Coffee Shop".equals(c.name())).findFirst().orElseThrow();
        assertEquals(0, BigDecimal.ZERO.compareTo(coffee.actual()),
                "the 4.10 purchase and its 4.10 refund must cancel");

        // And the drill-down must agree with the tile it sits behind.
        assertEquals(2, analysisService.categoryTransactions("2026-05", String.valueOf(coffeeId)).size(),
                "both the charge and the refund belong in the category's list");
    }

    /**
     * A card payment is money-in, not negative spend, and was only ever kept out of the
     * total by being EXCLUDED — a merchant-cache rule rather than a property of the
     * transaction. Un-categorizing one sent it through signedSpend into the parked
     * "Other" bucket as a negative, so the month dropped by its full amount and Other
     * showed a large negative figure. Parked money must count as spend or not at all;
     * it must never count as *negative* spend.
     */
    @Test
    void aParkedCardPaymentDoesNotSubtractFromTheMonth() {
        TransactionMapping payment = mappingRepo.findByAnalysisRunId(runId).stream()
                .filter(m -> "PAYMENT".equals(
                        txnRepo.findById(m.getTransactionId()).orElseThrow().getType()))
                .findFirst().orElseThrow();

        // Un-categorize it — back to PARKED, and the remembered exclusion is forgotten.
        mappingService.assign(runId, payment.getTransactionId(), null);

        AnalysisService.MonthSummary ms = analysisService.month("2026-05");
        assertEquals(0, new BigDecimal("4.45").compareTo(ms.totalActual()),
                "coffee 4.10 + metro 0.35, unchanged — the 150 payment is not negative spend");

        AnalysisService.CategoryRow other = ms.categories().stream()
                .filter(c -> c.entryId() == null).findFirst().orElseThrow();
        assertEquals(0, BigDecimal.ZERO.compareTo(other.actual()),
                "and it must not appear as a negative Other");
    }

    @Test
    void rollingCountsOnlyCompleteMonths() {
        // A month must be flagged complete to feed the rolling average.
        assertEquals(0, analysisService.rolling().months(), "no complete months yet");

        analysisService.setMonthComplete("2026-05", true);
        AnalysisService.RollingSummary r = analysisService.rolling();
        assertEquals(1, r.months());
        assertEquals(0, new BigDecimal("4.45").compareTo(r.avgActual()));
        assertEquals(0, new BigDecimal("100.00").compareTo(r.totalBudget()));
    }

    @Test
    void trendsHasOnePointForTheActualMonth() {
        List<AnalysisService.TrendPoint> trends = analysisService.trends();
        assertEquals(1, trends.size());
        assertEquals("2026-05", trends.get(0).month());
        assertEquals(0, new BigDecimal("4.45").compareTo(trends.get(0).totalActual()));
    }

    @Test
    void monthsWithStatusListsTheActualMonth() {
        List<AnalysisService.MonthInfo> months = analysisService.monthsWithStatus();
        assertEquals(1, months.size());
        assertEquals("2026-05", months.get(0).month());
        assertTrue(!months.get(0).complete(), "not marked complete yet");
    }

    @Test
    void categoryTransactionsListsTheItemsBehindACategory() {
        Long coffeeId = entryRepo.findAll().stream()
                .filter(e -> "Coffee Shop".equals(e.getName())).findFirst().orElseThrow().getId();

        List<AnalysisService.TxnLine> coffee = analysisService.categoryTransactions("2026-05", coffeeId.toString());
        assertEquals(1, coffee.size());
        assertTrue(coffee.get(0).description().contains("COFFEE SHOP"));
        assertEquals(0, new BigDecimal("4.10").compareTo(coffee.get(0).amount()));
        assertNotNull(coffee.get(0).transactionId(), "id is needed to reassign the row");
        assertNotNull(coffee.get(0).analysisRunId(), "run id is needed to reassign the row");

        // Payment was excluded, so "other" (parked) is empty.
        assertEquals(0, analysisService.categoryTransactions("2026-05", "other").size());
    }

    @Test
    void categoryTrendPlotsOnlyCompleteMonths() {
        Long coffeeId = entryRepo.findAll().stream()
                .filter(e -> "Coffee Shop".equals(e.getName())).findFirst().orElseThrow().getId();

        // The Rolling screen (where this dialog is opened) is defined by complete
        // months, so a month that isn't marked complete must not appear.
        assertTrue(analysisService.categoryTrend(coffeeId.toString()).isEmpty(),
                "no month is complete yet, so there is nothing to plot");

        analysisService.setMonthComplete("2026-05", true);

        List<AnalysisService.CategoryTrendPoint> trend = analysisService.categoryTrend(coffeeId.toString());
        assertEquals(1, trend.size());
        assertEquals("2026-05", trend.get(0).month());
        assertEquals(0, new BigDecimal("4.10").compareTo(trend.get(0).actual()));
        assertEquals(0, new BigDecimal("50.00").compareTo(trend.get(0).budget()), "constant category allowance");
    }
}
