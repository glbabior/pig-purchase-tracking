package com.pigpurchases.service;

import com.pigpurchases.model.AnalysisRun;
import com.pigpurchases.model.AnalysisRunSource;
import com.pigpurchases.model.BudgetEntry;
import com.pigpurchases.model.Transaction;
import com.pigpurchases.model.TransactionMapping;
import com.pigpurchases.model.AppSettings;
import com.pigpurchases.repository.AnalysisRunRepository;
import com.pigpurchases.repository.AnalysisRunSourceRepository;
import com.pigpurchases.repository.AppSettingsRepository;
import com.pigpurchases.repository.BudgetEntryRepository;
import com.pigpurchases.repository.TransactionMappingRepository;
import com.pigpurchases.repository.TransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns completed mapping runs into budget-vs-actual analysis: one month, a
 * rolling average across months, and a trend series.
 *
 * <p><b>What counts as spend.</b> Each mapped transaction contributes its
 * absolute amount as spend, with direction taken from its type so refunds net
 * out: money-out types (purchases, withdrawals, fees) add, money-in types
 * (payments, credits, deposits) subtract. EXCLUDED transactions — parser
 * transfers and anything the user marked "not spend" — never count.
 *
 * <p><b>What "budget" means.</b> A category's budget is its monthly allowance;
 * the month's total budget is the sum of all category allowances. Parked
 * transactions are collected as an unbudgeted "Other" line: real spend with no
 * allowance to compare against.
 *
 * <p><b>Rolling</b> is the average of each figure across every mapped month, so
 * it reads as "a typical month." With one month mapped it equals that month.
 */
@Service
public class AnalysisService {

    @Autowired private AnalysisRunRepository runRepository;
    @Autowired private AnalysisRunSourceRepository runSourceRepository;
    @Autowired private TransactionMappingRepository mappingRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private BudgetEntryRepository budgetEntryRepository;
    @Autowired private AppSettingsRepository appSettingsRepository;

    private static final BigDecimal MONTHS_PER_YEAR = BigDecimal.valueOf(12);

    /** entryId is null for the synthetic "Other" (parked) row, which has no budget. */
    public record CategoryRow(Long entryId, String name, BigDecimal budget,
                              BigDecimal actual, BigDecimal variance) {}

    public record MonthSummary(String month, BigDecimal totalBudget, BigDecimal totalActual,
                               BigDecimal variance, BigDecimal excluded, List<CategoryRow> categories) {}

    public record RollingSummary(int months, BigDecimal totalBudget, BigDecimal avgActual,
                                 BigDecimal variance, List<CategoryRow> categories) {}

    public record TrendPoint(String month, BigDecimal totalActual, BigDecimal totalBudget) {}

    /** One transaction behind a category's total, for the click-through detail (and reassigning it). */
    public record TxnLine(Long transactionId, String date, String description, String vendor,
                          BigDecimal amount, String type) {}

    /** Months that have a completed mapping run, newest first. */
    @Transactional(readOnly = true)
    public List<String> mappedMonths() {
        List<String> months = new ArrayList<>();
        for (AnalysisRun run : runRepository.findAllByOrderByMonthDesc()) {
            if (run.getStatus() == AnalysisRun.Status.MAPPED) {
                months.add(run.getMonth());
            }
        }
        return months;
    }

    @Transactional(readOnly = true)
    public MonthSummary month(String month) {
        AnalysisRun run = runRepository.findByMonth(month)
                .filter(r -> r.getStatus() == AnalysisRun.Status.MAPPED)
                .orElseThrow(() -> new IllegalArgumentException("No completed mapping run for " + month));
        return summarize(run, budgetEntryRepository.findAll());
    }

    @Transactional(readOnly = true)
    public RollingSummary rolling() {
        List<BudgetEntry> entries = budgetEntryRepository.findAll();
        List<MonthSummary> all = mappedRuns().stream().map(r -> summarize(r, entries)).toList();

        BigDecimal totalBudget = monthlyAllowance();
        if (all.isEmpty()) {
            return new RollingSummary(0, totalBudget, BigDecimal.ZERO, totalBudget, categoryRows(entries,
                    new HashMap<>(), BigDecimal.ZERO, 1, totalBudget));
        }

        int n = all.size();
        BigDecimal avgActual = all.stream().map(MonthSummary::totalActual)
                .reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);

        // Sum each category's actual across months, then average.
        Map<Long, BigDecimal> summedByEntry = new HashMap<>();
        BigDecimal summedOther = BigDecimal.ZERO;
        for (MonthSummary ms : all) {
            for (CategoryRow row : ms.categories()) {
                if (row.entryId() == null) {
                    summedOther = summedOther.add(row.actual());
                } else {
                    summedByEntry.merge(row.entryId(), row.actual(), BigDecimal::add);
                }
            }
        }
        List<CategoryRow> categories = categoryRows(entries, summedByEntry, summedOther, n, totalBudget);
        return new RollingSummary(n, totalBudget, avgActual, totalBudget.subtract(avgActual), categories);
    }

    @Transactional(readOnly = true)
    public List<TrendPoint> trends() {
        List<BudgetEntry> entries = budgetEntryRepository.findAll();
        List<TrendPoint> points = new ArrayList<>();
        List<AnalysisRun> runs = new ArrayList<>(mappedRuns());
        runs.sort(Comparator.comparing(AnalysisRun::getMonth)); // oldest first, for a left-to-right timeline
        for (AnalysisRun run : runs) {
            MonthSummary ms = summarize(run, entries);
            points.add(new TrendPoint(run.getMonth(), ms.totalActual(), ms.totalBudget()));
        }
        return points;
    }

    // ---- internals ---------------------------------------------------------

    private List<AnalysisRun> mappedRuns() {
        return runRepository.findAllByOrderByMonthDesc().stream()
                .filter(r -> r.getStatus() == AnalysisRun.Status.MAPPED).toList();
    }

    /** One run's budget-vs-actual, per category plus the "Other" and excluded totals. */
    private MonthSummary summarize(AnalysisRun run, List<BudgetEntry> entries) {
        Map<Long, Transaction> txnById = transactionsForRun(run);

        Map<Long, BigDecimal> byEntry = new HashMap<>();
        BigDecimal other = BigDecimal.ZERO;
        BigDecimal excluded = BigDecimal.ZERO;

        for (TransactionMapping m : mappingRepository.findByAnalysisRunId(run.getId())) {
            Transaction txn = txnById.get(m.getTransactionId());
            if (txn == null) {
                continue;
            }
            if (m.getStatus() == TransactionMapping.Status.EXCLUDED) {
                excluded = excluded.add(txn.getAmount() != null ? txn.getAmount().abs() : BigDecimal.ZERO);
                continue;
            }
            BigDecimal spend = signedSpend(txn);
            if (m.getStatus() == TransactionMapping.Status.PARKED || m.getBudgetEntryId() == null) {
                other = other.add(spend);
            } else {
                byEntry.merge(m.getBudgetEntryId(), spend, BigDecimal::add);
            }
        }

        BigDecimal totalBudget = monthlyAllowance();
        List<CategoryRow> categories = categoryRows(entries, byEntry, other, 1, totalBudget);
        BigDecimal totalActual = categories.stream().map(CategoryRow::actual)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new MonthSummary(run.getMonth(), totalBudget, round(totalActual),
                round(totalBudget.subtract(totalActual)), round(excluded), categories);
    }

    /**
     * Build the per-category rows. {@code actual} values are divided by
     * {@code divisor} (months) so this serves both a single month (divisor 1)
     * and a rolling average (divisor = month count).
     */
    private List<CategoryRow> categoryRows(List<BudgetEntry> entries, Map<Long, BigDecimal> actualByEntry,
                                           BigDecimal otherTotal, int divisor, BigDecimal monthlyAllowance) {
        BigDecimal div = BigDecimal.valueOf(Math.max(divisor, 1));
        List<CategoryRow> rows = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO;
        for (BudgetEntry entry : entries) {
            BigDecimal budget = entry.getMonthlyAllowance() != null ? entry.getMonthlyAllowance() : BigDecimal.ZERO;
            allocated = allocated.add(budget);
            BigDecimal actual = actualByEntry.getOrDefault(entry.getId(), BigDecimal.ZERO)
                    .divide(div, 2, RoundingMode.HALF_UP);
            rows.add(new CategoryRow(entry.getId(), entry.getName(), round(budget), actual,
                    round(budget.subtract(actual))));
        }
        rows.sort(Comparator.comparing(r -> r.name() == null ? "" : r.name().toLowerCase()));
        // "Other" last. Its budget is the discretionary remainder — whatever of the
        // monthly allowance isn't allocated to a category — so category budgets plus
        // Other's budget always sum to the monthly allowance.
        BigDecimal otherBudget = monthlyAllowance.subtract(allocated);
        BigDecimal other = otherTotal.divide(div, 2, RoundingMode.HALF_UP);
        if (otherBudget.signum() != 0 || other.signum() != 0) {
            rows.add(new CategoryRow(null, "Other (discretionary)", round(otherBudget), other,
                    round(otherBudget.subtract(other))));
        }
        return rows;
    }

    private Map<Long, Transaction> transactionsForRun(AnalysisRun run) {
        Map<Long, Transaction> byId = new LinkedHashMap<>();
        for (AnalysisRunSource link : runSourceRepository.findByAnalysisRunId(run.getId())) {
            for (Transaction txn : transactionRepository.findByStatementImportId(link.getStatementImportId())) {
                byId.put(txn.getId(), txn);
            }
        }
        return byId;
    }

    /** The month's top-line budget: the annual budget from settings divided by 12. */
    private BigDecimal monthlyAllowance() {
        BigDecimal annual = appSettingsRepository.findById(1L)
                .map(AppSettings::getAnnualBudget).orElse(BigDecimal.ZERO);
        if (annual == null) {
            annual = BigDecimal.ZERO;
        }
        return annual.divide(MONTHS_PER_YEAR, 2, RoundingMode.HALF_UP);
    }

    /** The transactions behind one category's total for a month; key is an entry id or "other". */
    @Transactional(readOnly = true)
    public List<TxnLine> categoryTransactions(String month, String categoryKey) {
        AnalysisRun run = runRepository.findByMonth(month)
                .filter(r -> r.getStatus() == AnalysisRun.Status.MAPPED)
                .orElseThrow(() -> new IllegalArgumentException("No completed mapping run for " + month));
        boolean other = "other".equalsIgnoreCase(categoryKey);
        Long entryId = null;
        if (!other) {
            try {
                entryId = Long.valueOf(categoryKey);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Unknown category: " + categoryKey);
            }
        }

        Map<Long, Transaction> txnById = transactionsForRun(run);
        List<TxnLine> lines = new ArrayList<>();
        for (TransactionMapping m : mappingRepository.findByAnalysisRunId(run.getId())) {
            if (m.getStatus() == TransactionMapping.Status.EXCLUDED) {
                continue;
            }
            boolean isParked = m.getStatus() == TransactionMapping.Status.PARKED || m.getBudgetEntryId() == null;
            boolean matches = other ? isParked : (!isParked && entryId.equals(m.getBudgetEntryId()));
            if (!matches) {
                continue;
            }
            Transaction txn = txnById.get(m.getTransactionId());
            if (txn == null) {
                continue;
            }
            lines.add(new TxnLine(txn.getId(),
                    txn.getTransactionDate() != null ? txn.getTransactionDate().toString() : null,
                    txn.getDescription(), txn.getVendor(), round(signedSpend(txn)), txn.getType()));
        }
        lines.sort(Comparator.comparing(l -> l.date() == null ? "" : l.date()));
        return lines;
    }

    /** Money out adds to spend; money in (refunds, payments, deposits) subtracts. */
    private static BigDecimal signedSpend(Transaction txn) {
        BigDecimal amount = txn.getAmount() != null ? txn.getAmount().abs() : BigDecimal.ZERO;
        String type = txn.getType();
        if ("PAYMENT".equals(type) || "CREDIT".equals(type) || "DEPOSIT".equals(type)) {
            return amount.negate();
        }
        return amount;
    }

    private static BigDecimal round(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
