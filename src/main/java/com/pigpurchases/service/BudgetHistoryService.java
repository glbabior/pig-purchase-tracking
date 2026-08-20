package com.pigpurchases.service;

import com.pigpurchases.model.AnnualBudgetEra;
import com.pigpurchases.model.AppSettings;
import com.pigpurchases.model.BudgetAmountEra;
import com.pigpurchases.model.BudgetEntry;
import com.pigpurchases.repository.AnnualBudgetEraRepository;
import com.pigpurchases.repository.AppSettingsRepository;
import com.pigpurchases.repository.BudgetAmountEraRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Budgets change over time; analysis compares each month against the budget that
 * was in force <b>that month</b>. This service owns both halves of that: recording
 * a change as an era, and resolving "what was the budget for month m?".
 *
 * <p><b>The fallback is the compatibility guarantee.</b> An entry (or the annual
 * budget) with no era rows resolves to its current value for every month — which is
 * exactly the pre-era behaviour, and what a database restored from an old backup
 * gets. Era rows appear only when a value is changed "going forward"; at that first
 * change the old value is recorded as a since-the-beginning era ({@code startMonth
 * = null}) so history keeps meaning what it meant.
 *
 * <p><b>Two kinds of edit</b>, chosen by the user in the edit dialog:
 * <ul>
 *   <li><b>Change going forward</b> — a new era starts in the current calendar
 *       month. Changing the same value twice in one month updates that month's era
 *       rather than stacking a zero-width one.</li>
 *   <li><b>Correct</b> — "this was always the amount": the value in force now is
 *       amended in place (the latest era when eras exist), moving no boundary.
 *       Fixing an <i>older</i> era is done through the era list itself.</li>
 * </ul>
 *
 * <p>Deleting an era merges its months back into the era before it. Whenever only
 * one era would remain, it is dropped entirely — one era covering all time is the
 * same statement as no history, and keeping the no-rows form means the fallback
 * path stays the common path.
 */
@Service
public class BudgetHistoryService {

    @Autowired private BudgetAmountEraRepository eraRepository;
    @Autowired private AnnualBudgetEraRepository annualEraRepository;
    @Autowired private AppSettingsRepository appSettingsRepository;

    // ---- resolution ---------------------------------------------------------

    /**
     * A read-once, resolve-many snapshot of every era. Analysis summarizes many
     * months in one request (rolling, trends), so resolving through the repository
     * each time would be a query per entry per month.
     */
    public static final class Resolver {
        private final Map<Long, List<BudgetAmountEra>> erasByEntry;
        private final List<AnnualBudgetEra> annualEras;
        private final BigDecimal currentAnnual;

        private Resolver(Map<Long, List<BudgetAmountEra>> erasByEntry,
                         List<AnnualBudgetEra> annualEras, BigDecimal currentAnnual) {
            this.erasByEntry = erasByEntry;
            this.annualEras = annualEras;
            this.currentAnnual = currentAnnual;
        }

        /** The entry's monthly budget in force for {@code month} ({@code YYYY-MM}). */
        public BigDecimal amount(BudgetEntry entry, String month) {
            BigDecimal current = entry.getMonthlyAllowance() != null
                    ? entry.getMonthlyAllowance() : BigDecimal.ZERO;
            List<BudgetAmountEra> eras = erasByEntry.get(entry.getId());
            if (eras == null || eras.isEmpty()) {
                return current;
            }
            BudgetAmountEra best = null;
            BudgetAmountEra earliest = null;
            for (BudgetAmountEra era : eras) {
                String start = era.getStartMonth();
                if (start == null || start.compareTo(month) <= 0) {
                    if (best == null || compareStarts(best.getStartMonth(), start) < 0) {
                        best = era;
                    }
                }
                if (earliest == null || compareStarts(start, earliest.getStartMonth()) < 0) {
                    earliest = era;
                }
            }
            // A month before every recorded era can only happen if the since-the-beginning
            // row was deleted by hand; the earliest era is the closest statement on record.
            BudgetAmountEra resolved = best != null ? best : earliest;
            return resolved != null && resolved.getAmount() != null ? resolved.getAmount() : current;
        }

        /** The annual budget in force for {@code month}. */
        public BigDecimal annual(String month) {
            if (annualEras.isEmpty()) {
                return currentAnnual;
            }
            AnnualBudgetEra best = null;
            AnnualBudgetEra earliest = null;
            for (AnnualBudgetEra era : annualEras) {
                String start = era.getStartMonth();
                if (start == null || start.compareTo(month) <= 0) {
                    if (best == null || compareStarts(best.getStartMonth(), start) < 0) {
                        best = era;
                    }
                }
                if (earliest == null || compareStarts(start, earliest.getStartMonth()) < 0) {
                    earliest = era;
                }
            }
            AnnualBudgetEra resolved = best != null ? best : earliest;
            return resolved != null && resolved.getAmount() != null ? resolved.getAmount() : currentAnnual;
        }

        /** null start = "since the beginning" sorts before every real month. */
        private static int compareStarts(String a, String b) {
            if (a == null && b == null) return 0;
            if (a == null) return -1;
            if (b == null) return 1;
            return a.compareTo(b);
        }
    }

    @Transactional(readOnly = true)
    public Resolver resolver() {
        Map<Long, List<BudgetAmountEra>> byEntry = new HashMap<>();
        for (BudgetAmountEra era : eraRepository.findAll()) {
            byEntry.computeIfAbsent(era.getBudgetEntryId(), k -> new ArrayList<>()).add(era);
        }
        BigDecimal annual = appSettingsRepository.findById(1L)
                .map(AppSettings::getAnnualBudget).orElse(BigDecimal.ZERO);
        return new Resolver(byEntry, annualEraRepository.findAll(),
                annual != null ? annual : BigDecimal.ZERO);
    }

    // ---- recording changes --------------------------------------------------

    /** The current calendar month as the {@code YYYY-MM} token eras are keyed by. */
    public String currentMonth() {
        return YearMonth.now().toString();
    }

    /**
     * Record "the amount changed going forward" and update the entry's current
     * value. On the first change ever, the old value is written as the
     * since-the-beginning era; a second change in the same calendar month updates
     * this month's era instead of stacking a zero-width one.
     */
    @Transactional
    public void changeEntryAmountForward(BudgetEntry entry, BigDecimal newAmount) {
        BigDecimal old = entry.getMonthlyAllowance() != null ? entry.getMonthlyAllowance() : BigDecimal.ZERO;
        if (old.compareTo(newAmount) == 0) {
            return;
        }
        String month = currentMonth();
        List<BudgetAmountEra> eras = eraRepository.findByBudgetEntryId(entry.getId());
        if (eras.isEmpty()) {
            eraRepository.save(new BudgetAmountEra(entry.getId(), null, old));
        }
        BudgetAmountEra thisMonth = eras.stream()
                .filter(e -> month.equals(e.getStartMonth())).findFirst().orElse(null);
        if (thisMonth != null) {
            thisMonth.setAmount(newAmount);
            eraRepository.save(thisMonth);
        } else {
            eraRepository.save(new BudgetAmountEra(entry.getId(), month, newAmount));
        }
        entry.setMonthlyAllowance(newAmount);
    }

    /**
     * Record "this was always the amount": amend the value in force now without
     * moving any boundary. With history, that is the latest era; without, just the
     * entry's current value.
     */
    @Transactional
    public void correctEntryAmount(BudgetEntry entry, BigDecimal newAmount) {
        latestEra(eraRepository.findByBudgetEntryId(entry.getId())).ifPresent(era -> {
            era.setAmount(newAmount);
            eraRepository.save(era);
        });
        entry.setMonthlyAllowance(newAmount);
    }

    /**
     * Change one era's amount in place. Amending the latest era must also change
     * the entry's current value — they are the same fact stated twice.
     */
    @Transactional
    public void amendEra(BudgetEntry entry, Long eraId, BigDecimal newAmount) {
        List<BudgetAmountEra> eras = eraRepository.findByBudgetEntryId(entry.getId());
        BudgetAmountEra target = eras.stream().filter(e -> e.getId().equals(eraId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No such budget era: " + eraId));
        target.setAmount(newAmount);
        eraRepository.save(target);
        if (latestEra(eras).map(e -> e.getId().equals(eraId)).orElse(false)) {
            entry.setMonthlyAllowance(newAmount);
        }
    }

    /**
     * Delete an era boundary, merging its months into the era before it (or, for
     * the since-the-beginning era, into the one after). If a single era would
     * remain it is dropped too — one era covering all time is the same statement
     * as no history — and the entry's current value is re-synced to whatever the
     * latest surviving statement says.
     */
    @Transactional
    public void deleteEra(BudgetEntry entry, Long eraId) {
        List<BudgetAmountEra> eras = new ArrayList<>(eraRepository.findByBudgetEntryId(entry.getId()));
        BudgetAmountEra target = eras.stream().filter(e -> e.getId().equals(eraId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No such budget era: " + eraId));
        eras.remove(target);
        eraRepository.delete(target);
        if (eras.size() == 1) {
            entry.setMonthlyAllowance(eras.get(0).getAmount());
            eraRepository.delete(eras.get(0));
            return;
        }
        latestEra(eras).ifPresent(latest -> entry.setMonthlyAllowance(latest.getAmount()));
    }

    /** Every era for an entry, oldest first (since-the-beginning row first). */
    @Transactional(readOnly = true)
    public List<BudgetAmountEra> erasFor(Long entryId) {
        return eraRepository.findByBudgetEntryId(entryId).stream()
                .sorted(Comparator.comparing(BudgetAmountEra::getStartMonth,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }

    /** Cleanup when an entry is deleted; its history goes with it. */
    @Transactional
    public void deleteErasFor(Long entryId) {
        eraRepository.deleteByBudgetEntryId(entryId);
    }

    private static Optional<BudgetAmountEra> latestEra(List<BudgetAmountEra> eras) {
        return eras.stream().max(Comparator.comparing(BudgetAmountEra::getStartMonth,
                Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    // ---- the annual budget, same shape ---------------------------------------

    /** Mirror of {@link #changeEntryAmountForward} for the annual budget. */
    @Transactional
    public void changeAnnualBudgetForward(AppSettings settings, BigDecimal newAmount) {
        BigDecimal old = settings.getAnnualBudget() != null ? settings.getAnnualBudget() : BigDecimal.ZERO;
        if (old.compareTo(newAmount) == 0) {
            return;
        }
        String month = currentMonth();
        List<AnnualBudgetEra> eras = annualEraRepository.findAll();
        if (eras.isEmpty()) {
            annualEraRepository.save(new AnnualBudgetEra(null, old));
        }
        AnnualBudgetEra thisMonth = eras.stream()
                .filter(e -> month.equals(e.getStartMonth())).findFirst().orElse(null);
        if (thisMonth != null) {
            thisMonth.setAmount(newAmount);
            annualEraRepository.save(thisMonth);
        } else {
            annualEraRepository.save(new AnnualBudgetEra(month, newAmount));
        }
        settings.setAnnualBudget(newAmount);
    }

    /** Mirror of {@link #correctEntryAmount} for the annual budget. */
    @Transactional
    public void correctAnnualBudget(AppSettings settings, BigDecimal newAmount) {
        annualEraRepository.findAll().stream()
                .max(Comparator.comparing(AnnualBudgetEra::getStartMonth,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .ifPresent(era -> {
                    era.setAmount(newAmount);
                    annualEraRepository.save(era);
                });
        settings.setAnnualBudget(newAmount);
    }

    /** Every annual-budget era, oldest first. */
    @Transactional(readOnly = true)
    public List<AnnualBudgetEra> annualEras() {
        return annualEraRepository.findAll().stream()
                .sorted(Comparator.comparing(AnnualBudgetEra::getStartMonth,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }
}
