package com.pigpurchases.service;

import com.pigpurchases.model.BudgetEntry;
import com.pigpurchases.model.MonthStatus;
import com.pigpurchases.model.Transaction;
import com.pigpurchases.model.TransactionMapping;
import com.pigpurchases.model.AppSettings;
import com.pigpurchases.repository.AppSettingsRepository;
import com.pigpurchases.repository.BudgetEntryRepository;
import com.pigpurchases.repository.MonthStatusRepository;
import com.pigpurchases.repository.TransactionMappingRepository;
import com.pigpurchases.repository.TransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Budget-vs-actual analysis, grouped by each transaction's <b>actual date</b>
 * (not the statement or run it arrived in). A transaction's category comes from
 * its mapping — matched to a budget entry, PARKED as "Other", or EXCLUDED — so a
 * statement whose billing cycle straddles two months contributes each transaction
 * to the calendar month it actually happened in.
 *
 * <p><b>What counts as spend.</b> Money-out types (purchases, withdrawals, fees)
 * add; money-in types (payments, credits, deposits) subtract, so refunds net out.
 * PARKED transactions collect as an unbudgeted "Other" line. EXCLUDED (transfers
 * and anything marked "not spend") are reported separately, never as spend.
 *
 * <p><b>Completeness.</b> A calendar month only feeds the rolling ("typical
 * month") average once it's flagged complete (see {@link MonthStatus}) — a
 * billing cycle that closes mid-month leaves the tail of a month in the next
 * statement, so a month isn't whole until that arrives. The analysis offers a
 * suggestion, but the flag is the user's to set.
 */
@Service
public class AnalysisService {

    @Autowired private TransactionMappingRepository mappingRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private BudgetEntryRepository budgetEntryRepository;
    @Autowired private AppSettingsRepository appSettingsRepository;
    @Autowired private MonthStatusRepository monthStatusRepository;

    private static final BigDecimal MONTHS_PER_YEAR = BigDecimal.valueOf(12);

    /** entryId is null for the synthetic "Other" (parked) row, which has no budget.
     *  count is the number of transactions behind the actual (total across months for rolling). */
    public record CategoryRow(Long entryId, String name, BigDecimal budget,
                              BigDecimal actual, BigDecimal variance, int count) {}

    /**
     * {@code excluded} is the NET of everything not counted as spend, so it can be zero
     * while excluded transactions exist — a card payment nets against the withdrawal that
     * paid it. {@code excludedCount} is therefore what the UI must gate the Excluded tile
     * on: gating on the net hid the tile in exactly that case, and since the tile is the
     * only way into the excluded list, a wrongly-excluded charge became unreachable.
     */
    public record MonthSummary(String month, BigDecimal totalBudget, BigDecimal totalActual,
                               BigDecimal variance, BigDecimal excluded, int excludedCount,
                               List<CategoryRow> categories) {}

    public record RollingSummary(int months, BigDecimal totalBudget, BigDecimal avgActual,
                                 BigDecimal variance, List<CategoryRow> categories) {}

    public record TrendPoint(String month, BigDecimal totalActual, BigDecimal totalBudget) {}

    /** One month's actual (and constant budget) for a single category, for its trend line. */
    public record CategoryTrendPoint(String month, BigDecimal actual, BigDecimal budget) {}

    /** One transaction behind a category's total, for the click-through detail (and reassigning it).
     *  {@code status} is the mapping status, so the detail list can tell a standing
     *  exclusion apart from a one-off one rather than showing both the same way. */
    public record TxnLine(Long transactionId, Long analysisRunId, String date, String description,
                          String vendor, BigDecimal amount, String type, String status) {}

    /** A calendar month that has mapped transactions, with its completeness state. */
    public record MonthInfo(String month, boolean complete, boolean suggested, int unmapped) {}

    // ---- month list & completeness ----------------------------------------

    /** Calendar months that have mapped transactions, newest first, with completeness. */
    @Transactional(readOnly = true)
    public List<MonthInfo> monthsWithStatus() {
        Map<String, MonthAgg> byMonth = aggregateByActualMonth();
        Map<String, Integer> unmappedByMonth = unmappedCountByMonth();
        Set<String> completeMonths = completeMonths();
        String suggestThrough = suggestCompleteThrough(); // months strictly before this are "likely complete"

        List<String> months = new ArrayList<>(new TreeSet<>(byMonth.keySet()));
        months.sort(Comparator.reverseOrder());
        List<MonthInfo> out = new ArrayList<>();
        for (String m : months) {
            boolean complete = completeMonths.contains(m);
            boolean suggested = suggestThrough != null && m.compareTo(suggestThrough) < 0;
            out.add(new MonthInfo(m, complete, suggested, unmappedByMonth.getOrDefault(m, 0)));
        }
        return out;
    }

    @Transactional
    public void setMonthComplete(String month, boolean complete) {
        MonthStatus status = monthStatusRepository.findById(month)
                .orElseGet(() -> new MonthStatus(month, false));
        status.setComplete(complete);
        monthStatusRepository.save(status);
    }

    // ---- per-month & rolling ----------------------------------------------

    @Transactional(readOnly = true)
    public MonthSummary month(String month) {
        MonthAgg agg = aggregateByActualMonth().getOrDefault(month, new MonthAgg());
        return summarize(month, agg, budgetEntryRepository.findAll());
    }

    @Transactional(readOnly = true)
    public RollingSummary rolling() {
        List<BudgetEntry> entries = budgetEntryRepository.findAll();
        BigDecimal totalBudget = monthlyAllowance();

        Map<String, MonthAgg> byMonth = aggregateByActualMonth();
        Set<String> complete = completeMonths();
        // Only complete months with data feed the average, so a partial month can't skew it.
        List<MonthSummary> all = byMonth.entrySet().stream()
                .filter(e -> complete.contains(e.getKey()))
                .map(e -> summarize(e.getKey(), e.getValue(), entries))
                .toList();

        if (all.isEmpty()) {
            return new RollingSummary(0, totalBudget, BigDecimal.ZERO, totalBudget,
                    categoryRows(entries, new HashMap<>(), BigDecimal.ZERO, 1, totalBudget, new HashMap<>(), 0));
        }

        int n = all.size();
        BigDecimal avgActual = all.stream().map(MonthSummary::totalActual)
                .reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);

        // Actuals average over the months; counts are the total number of transactions.
        Map<Long, BigDecimal> summedByEntry = new HashMap<>();
        Map<Long, Integer> countByEntry = new HashMap<>();
        BigDecimal summedOther = BigDecimal.ZERO;
        int otherCount = 0;
        for (MonthSummary ms : all) {
            for (CategoryRow row : ms.categories()) {
                if (row.entryId() == null) {
                    summedOther = summedOther.add(row.actual());
                    otherCount += row.count();
                } else {
                    summedByEntry.merge(row.entryId(), row.actual(), BigDecimal::add);
                    countByEntry.merge(row.entryId(), row.count(), Integer::sum);
                }
            }
        }
        List<CategoryRow> categories = categoryRows(entries, summedByEntry, summedOther, n, totalBudget,
                countByEntry, otherCount);
        return new RollingSummary(n, totalBudget, avgActual, totalBudget.subtract(avgActual), categories);
    }

    @Transactional(readOnly = true)
    public List<TrendPoint> trends() {
        List<BudgetEntry> entries = budgetEntryRepository.findAll();
        Map<String, MonthAgg> byMonth = aggregateByActualMonth();
        List<String> months = new ArrayList<>(byMonth.keySet());
        months.sort(Comparator.naturalOrder()); // oldest first, for a left-to-right timeline
        List<TrendPoint> points = new ArrayList<>();
        for (String m : months) {
            MonthSummary ms = summarize(m, byMonth.get(m), entries);
            points.add(new TrendPoint(m, ms.totalActual(), ms.totalBudget()));
        }
        return points;
    }

    /**
     * One category's actual spend month by month (most recent 12), for a line
     * chart. Budget is the category's current allowance, constant across months.
     *
     * <p>This is reached from the Rolling screen, which is defined by the months
     * the user marked complete, so only complete months are plotted — a partial
     * month would misrepresent the category's spend the same way it would skew the
     * rolling average. (The separate "Trend over time" chart deliberately shows
     * partial months; this per-category view does not.)
     */
    @Transactional(readOnly = true)
    public List<CategoryTrendPoint> categoryTrend(String categoryKey) {
        boolean other = "other".equalsIgnoreCase(categoryKey);
        Long entryId = other ? null : parseEntryId(categoryKey);

        List<BudgetEntry> entries = budgetEntryRepository.findAll();
        Map<String, MonthAgg> byMonth = aggregateByActualMonth();
        Set<String> complete = completeMonths();
        List<String> months = new ArrayList<>(byMonth.keySet());
        months.removeIf(m -> !complete.contains(m));
        months.sort(Comparator.naturalOrder());
        if (months.size() > 12) {
            months = months.subList(months.size() - 12, months.size());
        }

        List<CategoryTrendPoint> points = new ArrayList<>();
        for (String m : months) {
            final Long id = entryId;
            CategoryRow row = summarize(m, byMonth.get(m), entries).categories().stream()
                    .filter(c -> other ? c.entryId() == null : (c.entryId() != null && c.entryId().equals(id)))
                    .findFirst().orElse(null);
            BigDecimal actual = row != null ? row.actual() : BigDecimal.ZERO;
            BigDecimal budget = row != null ? row.budget() : BigDecimal.ZERO;
            points.add(new CategoryTrendPoint(m, actual, budget));
        }
        return points;
    }

    /** The transactions behind one category for a calendar month; key is an entry id, "other", or "__excluded__". */
    @Transactional(readOnly = true)
    public List<TxnLine> categoryTransactions(String month, String categoryKey) {
        boolean excluded = "__excluded__".equalsIgnoreCase(categoryKey);
        boolean other = "other".equalsIgnoreCase(categoryKey);
        Long entryId = (other || excluded) ? null : parseEntryId(categoryKey);

        Map<Long, Transaction> txnById = allTransactionsById();
        List<TxnLine> lines = new ArrayList<>();
        for (TransactionMapping m : mappingRepository.findAll()) {
            Transaction txn = txnById.get(m.getTransactionId());
            if (txn == null || txn.getTransactionDate() == null || !month.equals(yyyymm(txn.getTransactionDate()))) {
                continue;
            }
            // Same split as aggregateByActualMonth, so the drill-down always lists exactly
            // the transactions behind the figure the user clicked.
            boolean isExcluded = !inSpendBuckets(m, txn);
            if (excluded) {
                if (!isExcluded) continue;
            } else {
                if (isExcluded) continue;
                boolean isParked = m.getStatus() == TransactionMapping.Status.PARKED || m.getBudgetEntryId() == null;
                boolean matches = other ? isParked : (!isParked && entryId.equals(m.getBudgetEntryId()));
                if (!matches) continue;
            }
            lines.add(new TxnLine(txn.getId(), m.getAnalysisRunId(),
                    txn.getTransactionDate().toString(),
                    txn.getDescription(), txn.getVendor(), round(signedSpend(txn)), txn.getType(),
                    m.getStatus() == null ? null : m.getStatus().name()));
        }
        lines.sort(Comparator.comparing(l -> l.date() == null ? "" : l.date()));
        return lines;
    }

    // ---- internals ---------------------------------------------------------

    /** Per-category / other / excluded spend totals (and transaction counts) for one calendar month. */
    private static final class MonthAgg {
        final Map<Long, BigDecimal> byEntry = new HashMap<>();
        final Map<Long, Integer> countByEntry = new HashMap<>();
        BigDecimal other = BigDecimal.ZERO;
        BigDecimal excluded = BigDecimal.ZERO;
        int excludedCount = 0;
        int otherCount = 0;
    }

    /** Group every mapped transaction into its actual-date month. */
    private Map<String, MonthAgg> aggregateByActualMonth() {
        Map<Long, Transaction> txnById = allTransactionsById();
        Map<String, MonthAgg> byMonth = new HashMap<>();
        for (TransactionMapping m : mappingRepository.findAll()) {
            Transaction txn = txnById.get(m.getTransactionId());
            if (txn == null || txn.getTransactionDate() == null) {
                continue;
            }
            MonthAgg agg = byMonth.computeIfAbsent(yyyymm(txn.getTransactionDate()), k -> new MonthAgg());
            BigDecimal spend = signedSpend(txn);
            if (!inSpendBuckets(m, txn)) {
                agg.excluded = agg.excluded.add(spend);
                agg.excludedCount++;
            } else if (m.getStatus() == TransactionMapping.Status.PARKED || m.getBudgetEntryId() == null) {
                agg.other = agg.other.add(spend);
                agg.otherCount++;
            } else {
                agg.byEntry.merge(m.getBudgetEntryId(), spend, BigDecimal::add);
                agg.countByEntry.merge(m.getBudgetEntryId(), 1, Integer::sum);
            }
        }
        return byMonth;
    }

    /** Ingested-but-not-yet-mapped transactions per actual month — a signal that a month is incomplete. */
    private Map<String, Integer> unmappedCountByMonth() {
        Set<Long> mapped = new HashSet<>();
        for (TransactionMapping m : mappingRepository.findAll()) {
            mapped.add(m.getTransactionId());
        }
        Map<String, Integer> counts = new HashMap<>();
        for (Transaction t : transactionRepository.findAll()) {
            if (t.getTransactionDate() == null || mapped.contains(t.getId())) {
                continue;
            }
            counts.merge(yyyymm(t.getTransactionDate()), 1, Integer::sum);
        }
        return counts;
    }

    private MonthSummary summarize(String month, MonthAgg agg, List<BudgetEntry> entries) {
        BigDecimal totalBudget = monthlyAllowance();
        List<CategoryRow> categories = categoryRows(entries, agg.byEntry, agg.other, 1, totalBudget,
                agg.countByEntry, agg.otherCount);
        BigDecimal totalActual = categories.stream().map(CategoryRow::actual)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new MonthSummary(month, totalBudget, round(totalActual),
                round(totalBudget.subtract(totalActual)), round(agg.excluded), agg.excludedCount,
                categories);
    }

    /**
     * Build the per-category rows. {@code actual} values are divided by
     * {@code divisor} (months) so this serves both a single month (divisor 1)
     * and a rolling average (divisor = month count).
     */
    private List<CategoryRow> categoryRows(List<BudgetEntry> entries, Map<Long, BigDecimal> actualByEntry,
                                           BigDecimal otherTotal, int divisor, BigDecimal monthlyAllowance,
                                           Map<Long, Integer> countByEntry, int otherCount) {
        BigDecimal div = BigDecimal.valueOf(Math.max(divisor, 1));
        List<CategoryRow> rows = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO;
        for (BudgetEntry entry : entries) {
            BigDecimal budget = entry.getMonthlyAllowance() != null ? entry.getMonthlyAllowance() : BigDecimal.ZERO;
            allocated = allocated.add(budget);
            BigDecimal actual = actualByEntry.getOrDefault(entry.getId(), BigDecimal.ZERO)
                    .divide(div, 2, RoundingMode.HALF_UP);
            rows.add(new CategoryRow(entry.getId(), entry.getName(), round(budget), actual,
                    round(budget.subtract(actual)), countByEntry.getOrDefault(entry.getId(), 0)));
        }
        rows.sort(Comparator.comparing(r -> r.name() == null ? "" : r.name().toLowerCase()));
        BigDecimal otherBudget = monthlyAllowance.subtract(allocated);
        BigDecimal other = otherTotal.divide(div, 2, RoundingMode.HALF_UP);
        if (otherBudget.signum() != 0 || other.signum() != 0 || otherCount > 0) {
            rows.add(new CategoryRow(null, "Other (discretionary)", round(otherBudget), other,
                    round(otherBudget.subtract(other)), otherCount));
        }
        return rows;
    }

    private Map<Long, Transaction> allTransactionsById() {
        Map<Long, Transaction> byId = new HashMap<>();
        for (Transaction t : transactionRepository.findAll()) {
            byId.put(t.getId(), t);
        }
        return byId;
    }

    private Set<String> completeMonths() {
        Set<String> out = new HashSet<>();
        for (MonthStatus s : monthStatusRepository.findAll()) {
            if (s.isComplete()) {
                out.add(s.getMonth());
            }
        }
        return out;
    }

    /**
     * Heuristic suggestion: once a later month has mapped data, an earlier month's
     * billing cycles have almost certainly all landed, so months strictly before
     * the newest month with data are "likely complete." It's only a hint — the
     * user confirms, because a mid-month cycle can still leave a tail.
     */
    private String suggestCompleteThrough() {
        Map<Long, Transaction> txnById = allTransactionsById();
        String maxMonth = null;
        for (TransactionMapping m : mappingRepository.findAll()) {
            Transaction t = txnById.get(m.getTransactionId());
            if (t == null || t.getTransactionDate() == null) continue;
            String ym = yyyymm(t.getTransactionDate());
            if (maxMonth == null || ym.compareTo(maxMonth) > 0) {
                maxMonth = ym;
            }
        }
        return maxMonth; // months < maxMonth are suggested complete
    }

    private BigDecimal monthlyAllowance() {
        BigDecimal annual = appSettingsRepository.findById(1L)
                .map(AppSettings::getAnnualBudget).orElse(BigDecimal.ZERO);
        if (annual == null) {
            annual = BigDecimal.ZERO;
        }
        return annual.divide(MONTHS_PER_YEAR, 2, RoundingMode.HALF_UP);
    }

    private Long parseEntryId(String categoryKey) {
        try {
            return Long.valueOf(categoryKey);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Unknown category: " + categoryKey);
        }
    }

    private static String yyyymm(LocalDate date) {
        return YearMonth.from(date).toString(); // e.g. "2026-06"
    }

    /**
     * A card payment or an account deposit, which is not spend at all and must never
     * reach a category or the parked "Other" bucket.
     *
     * <p>Both were only ever kept out by being marked {@code EXCLUDED} — a merchant-cache
     * rule, not a property of the transaction. The moment one was parked or categorized
     * instead, {@link #signedSpend} negated it and it <i>subtracted</i> from the month:
     * un-categorizing a single card payment dropped the total by its full amount and
     * showed a large negative "Other". That inverts the "parked still counts as spend"
     * invariant rather than merely bending it — money-in became negative spend.
     *
     * <p>A {@code CREDIT} is deliberately not included. A refund genuinely reverses a
     * purchase in the same category, so netting it against spend is correct.
     */
    private static boolean isMoneyIn(Transaction txn) {
        String type = txn.getType();
        return "PAYMENT".equals(type) || "DEPOSIT".equals(type);
    }

    /**
     * True when this row belongs in a category or the parked "Other" bucket, rather than
     * in the excluded total. The single test, asked identically by the month aggregate and
     * by the click-through, so the drill-down always lists exactly the rows behind the
     * figure that was clicked.
     *
     * <p>Money in is normally kept out — see {@link #isMoneyIn} — <b>unless the user
     * assigned it to a category by hand.</b> That exception exists because the Bayside parser
     * has no {@code CREDIT} type: it types every positive line {@code DEPOSIT}, so a
     * debit-card refund, a merchant credit and a paycheck are indistinguishable by type.
     * Keeping all three out left a returned $200 purchase showing as $200 of spend, and
     * assigning the refund to its category appeared to work while changing nothing.
     *
     * <p>Only {@code MAPPED_MANUAL} qualifies. A parked or AI-guessed card payment still
     * cannot reach the spend buckets, which is what the exclusion was added for.
     */
    public static boolean inSpendBuckets(TransactionMapping m, Transaction txn) {
        if (!m.countsAsSpend()) {
            return false;
        }
        if (!isMoneyIn(txn)) {
            return true;
        }
        // The REASON, not just the status. A cached MANUAL rule also writes MAPPED_MANUAL,
        // onto whatever transaction matches its description — so testing the status alone
        // let one hand-assigned refund turn every future money-in row sharing that
        // description into negative spend, a paycheck under "MOBILE DEPOSIT" included.
        // Only a decision made about this exact row qualifies.
        return m.getStatus() == TransactionMapping.Status.MAPPED_MANUAL
                && m.getBudgetEntryId() != null
                && MappingService.ASSIGNED_BY_HAND.equals(m.getReason());
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
