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

    /** Overload for a change effective now — the ordinary case. */
    @Transactional
    public void changeEntryAmountForward(BudgetEntry entry, BigDecimal newAmount) {
        changeEntryAmountForward(entry, newAmount, currentMonth());
    }

    /**
     * Record "the amount changes as of {@code effectiveMonth}". On the first change
     * ever, the old value is written as the since-the-beginning era; a second change
     * effective the same month updates that month's era instead of stacking a
     * zero-width one. A no-op when the amount in force at that month already is
     * {@code newAmount} — re-saving what the screen shows must record nothing.
     *
     * <p>The effective month may be in the future ("the pass renews in September")
     * or the past ("this actually changed in January"): resolution is by month, so
     * either is just an era boundary. The entry's stored amount tracks the
     * <b>latest</b> era; what any screen shows for "now" is resolved per month, so
     * a future-dated change appears nowhere until its month arrives — and then
     * appears on its own.
     */
    @Transactional
    public void changeEntryAmountForward(BudgetEntry entry, BigDecimal newAmount, String effectiveMonth) {
        String month = validMonth(effectiveMonth);
        BigDecimal current = entry.getMonthlyAllowance() != null ? entry.getMonthlyAllowance() : BigDecimal.ZERO;
        List<BudgetAmountEra> eras = eraRepository.findByBudgetEntryId(entry.getId());
        if (inForceAt(eras, month, current).compareTo(newAmount) == 0) {
            return;
        }
        if (eras.isEmpty()) {
            eraRepository.save(new BudgetAmountEra(entry.getId(), null, current));
        }
        BudgetAmountEra atMonth = eras.stream()
                .filter(e -> month.equals(e.getStartMonth())).findFirst().orElse(null);
        if (atMonth != null) {
            atMonth.setAmount(newAmount);
            eraRepository.save(atMonth);
        } else {
            eraRepository.save(new BudgetAmountEra(entry.getId(), month, newAmount));
        }
        syncToLatest(entry);
    }

    /**
     * Record "this was always the amount": amend the era in force <b>now</b>
     * without moving any boundary. Not simply the latest era — with a future-dated
     * change pending, the latest era is the pending one, and a correction is about
     * the number the user is looking at today. Fixing a specific other era is done
     * through the era list.
     */
    @Transactional
    public void correctEntryAmount(BudgetEntry entry, BigDecimal newAmount) {
        List<BudgetAmountEra> eras = eraRepository.findByBudgetEntryId(entry.getId());
        if (eras.isEmpty()) {
            entry.setMonthlyAllowance(newAmount);
            return;
        }
        String now = currentMonth();
        BudgetAmountEra inForce = null;
        for (BudgetAmountEra era : eras) {
            String start = era.getStartMonth();
            if (start == null || start.compareTo(now) <= 0) {
                if (inForce == null || Resolver.compareStarts(inForce.getStartMonth(), start) < 0) {
                    inForce = era;
                }
            }
        }
        if (inForce == null) {
            inForce = eras.stream().min(Comparator.comparing(BudgetAmountEra::getStartMonth,
                    Comparator.nullsFirst(Comparator.naturalOrder()))).orElseThrow();
        }
        inForce.setAmount(newAmount);
        eraRepository.save(inForce);
        syncToLatest(entry);
    }

    /** The amount the eras (or the stored value, without any) put in force at {@code month}. */
    private static BigDecimal inForceAt(List<BudgetAmountEra> eras, String month, BigDecimal current) {
        if (eras.isEmpty()) {
            return current;
        }
        BudgetAmountEra best = null;
        BudgetAmountEra earliest = null;
        for (BudgetAmountEra era : eras) {
            String start = era.getStartMonth();
            if (start == null || start.compareTo(month) <= 0) {
                if (best == null || Resolver.compareStarts(best.getStartMonth(), start) < 0) {
                    best = era;
                }
            }
            if (earliest == null || Resolver.compareStarts(start, earliest.getStartMonth()) < 0) {
                earliest = era;
            }
        }
        BudgetAmountEra resolved = best != null ? best : earliest;
        return resolved != null && resolved.getAmount() != null ? resolved.getAmount() : current;
    }

    /** The stored amount tracks the latest era — same fact stated twice. */
    private void syncToLatest(BudgetEntry entry) {
        latestEra(eraRepository.findByBudgetEntryId(entry.getId()))
                .ifPresent(latest -> entry.setMonthlyAllowance(latest.getAmount()));
    }

    /** {@code YYYY-MM} or bust; a garbled month must fail the save, not write a stray era. */
    private static String validMonth(String month) {
        if (month == null || !month.matches("\\d{4}-(0[1-9]|1[0-2])")) {
            throw new IllegalArgumentException("Effective month must be YYYY-MM, got: " + month);
        }
        return month;
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

    /** Overload for a change effective now — the ordinary case. */
    @Transactional
    public void changeAnnualBudgetForward(AppSettings settings, BigDecimal newAmount) {
        changeAnnualBudgetForward(settings, newAmount, currentMonth());
    }

    /** Mirror of {@link #changeEntryAmountForward} for the annual budget. */
    @Transactional
    public void changeAnnualBudgetForward(AppSettings settings, BigDecimal newAmount, String effectiveMonth) {
        String month = validMonth(effectiveMonth);
        BigDecimal current = settings.getAnnualBudget() != null ? settings.getAnnualBudget() : BigDecimal.ZERO;
        List<AnnualBudgetEra> eras = annualEraRepository.findAll();
        if (annualInForceAt(eras, month, current).compareTo(newAmount) == 0) {
            return;
        }
        if (eras.isEmpty()) {
            annualEraRepository.save(new AnnualBudgetEra(null, current));
        }
        AnnualBudgetEra atMonth = eras.stream()
                .filter(e -> month.equals(e.getStartMonth())).findFirst().orElse(null);
        if (atMonth != null) {
            atMonth.setAmount(newAmount);
            annualEraRepository.save(atMonth);
        } else {
            annualEraRepository.save(new AnnualBudgetEra(month, newAmount));
        }
        annualEraRepository.findAll().stream()
                .max(Comparator.comparing(AnnualBudgetEra::getStartMonth,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .ifPresent(latest -> settings.setAnnualBudget(latest.getAmount()));
    }

    /** Mirror of {@link #correctEntryAmount}: amend the era in force now, not the latest. */
    @Transactional
    public void correctAnnualBudget(AppSettings settings, BigDecimal newAmount) {
        List<AnnualBudgetEra> eras = annualEraRepository.findAll();
        if (eras.isEmpty()) {
            settings.setAnnualBudget(newAmount);
            return;
        }
        String now = currentMonth();
        AnnualBudgetEra inForce = null;
        for (AnnualBudgetEra era : eras) {
            String start = era.getStartMonth();
            if (start == null || start.compareTo(now) <= 0) {
                if (inForce == null || Resolver.compareStarts(inForce.getStartMonth(), start) < 0) {
                    inForce = era;
                }
            }
        }
        if (inForce == null) {
            inForce = eras.stream().min(Comparator.comparing(AnnualBudgetEra::getStartMonth,
                    Comparator.nullsFirst(Comparator.naturalOrder()))).orElseThrow();
        }
        inForce.setAmount(newAmount);
        annualEraRepository.save(inForce);
        eras.stream().max(Comparator.comparing(AnnualBudgetEra::getStartMonth,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .ifPresent(latest -> settings.setAnnualBudget(latest.getAmount()));
    }

    /** The annual amount the eras (or the stored value, without any) put in force at {@code month}. */
    private static BigDecimal annualInForceAt(List<AnnualBudgetEra> eras, String month, BigDecimal current) {
        if (eras.isEmpty()) {
            return current;
        }
        AnnualBudgetEra best = null;
        AnnualBudgetEra earliest = null;
        for (AnnualBudgetEra era : eras) {
            String start = era.getStartMonth();
            if (start == null || start.compareTo(month) <= 0) {
                if (best == null || Resolver.compareStarts(best.getStartMonth(), start) < 0) {
                    best = era;
                }
            }
            if (earliest == null || Resolver.compareStarts(start, earliest.getStartMonth()) < 0) {
                earliest = era;
            }
        }
        AnnualBudgetEra resolved = best != null ? best : earliest;
        return resolved != null && resolved.getAmount() != null ? resolved.getAmount() : current;
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
