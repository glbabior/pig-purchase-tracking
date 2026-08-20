package com.pigpurchases.service;

import com.pigpurchases.model.AppSettings;
import com.pigpurchases.model.BudgetEntry;
import com.pigpurchases.repository.AnnualBudgetEraRepository;
import com.pigpurchases.repository.AppSettingsRepository;
import com.pigpurchases.repository.BudgetAmountEraRepository;
import com.pigpurchases.repository.BudgetEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The era rules: a forward change records the old amount as history, a correction
 * moves no boundary, and no rows at all means "the current value has always
 * applied" — the fallback that keeps every pre-era database and restored backup
 * behaving exactly as before.
 */
@SpringBootTest
@ActiveProfiles("test")
class BudgetHistoryServiceTest {

    @Autowired private BudgetHistoryService history;
    @Autowired private BudgetEntryRepository entryRepo;
    @Autowired private BudgetAmountEraRepository eraRepo;
    @Autowired private AnnualBudgetEraRepository annualEraRepo;
    @Autowired private AppSettingsRepository settingsRepo;

    private BudgetEntry entry;

    @BeforeEach
    void setUp() {
        eraRepo.deleteAll();
        annualEraRepo.deleteAll();
        entryRepo.deleteAll();
        entry = entryRepo.save(new BudgetEntry("Disney Ticket", new BigDecimal("230.00")));
    }

    private String monthBefore() {
        return YearMonth.now().minusMonths(1).toString();
    }

    @Test
    void aForwardChangeRecordsTheOldAmountAsHistory() {
        history.changeEntryAmountForward(entry, new BigDecimal("100.00"));

        var eras = history.erasFor(entry.getId());
        assertEquals(2, eras.size(), "old amount since the beginning, new amount from this month");
        assertNull(eras.get(0).getStartMonth());
        assertEquals(0, new BigDecimal("230.00").compareTo(eras.get(0).getAmount()));
        assertEquals(history.currentMonth(), eras.get(1).getStartMonth());
        assertEquals(0, new BigDecimal("100.00").compareTo(entry.getMonthlyAllowance()));

        var resolver = history.resolver();
        assertEquals(0, new BigDecimal("230.00").compareTo(resolver.amount(entry, monthBefore())),
                "past months keep the budget they were lived under");
        assertEquals(0, new BigDecimal("100.00").compareTo(resolver.amount(entry, history.currentMonth())));
    }

    @Test
    void changingTwiceInOneMonthUpdatesThatMonthsEra() {
        history.changeEntryAmountForward(entry, new BigDecimal("100.00"));
        history.changeEntryAmountForward(entry, new BigDecimal("90.00"));

        var eras = history.erasFor(entry.getId());
        assertEquals(2, eras.size(), "no zero-width era is stacked");
        assertEquals(0, new BigDecimal("90.00").compareTo(eras.get(1).getAmount()));
    }

    @Test
    void anUnchangedAmountRecordsNothing() {
        history.changeEntryAmountForward(entry, new BigDecimal("230.00"));
        assertTrue(history.erasFor(entry.getId()).isEmpty(),
                "no rows means current-value-always, and that must stay the common case");
    }

    @Test
    void aCorrectionMovesNoBoundary() {
        history.changeEntryAmountForward(entry, new BigDecimal("100.00"));
        history.correctEntryAmount(entry, new BigDecimal("95.00"));

        var eras = history.erasFor(entry.getId());
        assertEquals(2, eras.size());
        assertEquals(0, new BigDecimal("230.00").compareTo(eras.get(0).getAmount()), "history untouched");
        assertEquals(0, new BigDecimal("95.00").compareTo(eras.get(1).getAmount()));
        assertEquals(0, new BigDecimal("95.00").compareTo(entry.getMonthlyAllowance()));
    }

    @Test
    void aCorrectionWithNoHistoryJustSetsTheValue() {
        history.correctEntryAmount(entry, new BigDecimal("235.00"));
        assertTrue(history.erasFor(entry.getId()).isEmpty());
        assertEquals(0, new BigDecimal("235.00").compareTo(entry.getMonthlyAllowance()));
    }

    @Test
    void deletingTheOnlyChangeCollapsesBackToNoHistory() {
        history.changeEntryAmountForward(entry, new BigDecimal("100.00"));
        Long latestId = history.erasFor(entry.getId()).get(1).getId();

        history.deleteEra(entry, latestId);

        assertTrue(history.erasFor(entry.getId()).isEmpty(),
                "one era covering all time is the same statement as no history");
        assertEquals(0, new BigDecimal("230.00").compareTo(entry.getMonthlyAllowance()),
                "current value re-synced to what survives");
    }

    @Test
    void amendingTheLatestEraAlsoChangesTheCurrentValue() {
        history.changeEntryAmountForward(entry, new BigDecimal("100.00"));
        Long latestId = history.erasFor(entry.getId()).get(1).getId();

        history.amendEra(entry, latestId, new BigDecimal("110.00"));
        assertEquals(0, new BigDecimal("110.00").compareTo(entry.getMonthlyAllowance()),
                "the latest era and the current value are the same fact stated twice");

        // Amending an OLDER era rewrites history only; the current value stands.
        Long firstId = history.erasFor(entry.getId()).get(0).getId();
        history.amendEra(entry, firstId, new BigDecimal("225.00"));
        assertEquals(0, new BigDecimal("110.00").compareTo(entry.getMonthlyAllowance()));
        assertEquals(0, new BigDecimal("225.00").compareTo(history.resolver().amount(entry, "2000-01")));
    }

    @Test
    void aFutureDatedChangeShowsNowhereUntilItsMonth() {
        String nextMonth = YearMonth.now().plusMonths(1).toString();
        history.changeEntryAmountForward(entry, new BigDecimal("100.00"), nextMonth);

        var eras = history.erasFor(entry.getId());
        assertEquals(2, eras.size());
        assertEquals(nextMonth, eras.get(1).getStartMonth());

        var resolver = history.resolver();
        assertEquals(0, new BigDecimal("230.00").compareTo(resolver.amount(entry, history.currentMonth())),
                "this month still runs on the old budget");
        assertEquals(0, new BigDecimal("100.00").compareTo(resolver.amount(entry, nextMonth)),
                "the new amount takes effect in its month, on its own");
    }

    @Test
    void reSavingTheOnScreenAmountWithAPendingChangeRecordsNothing() {
        String nextMonth = YearMonth.now().plusMonths(1).toString();
        history.changeEntryAmountForward(entry, new BigDecimal("100.00"), nextMonth);

        // The dialog shows the amount in force NOW (230). Saving it back unchanged,
        // effective this month, must not write a stray this-month era of 230.
        history.changeEntryAmountForward(entry, new BigDecimal("230.00"), history.currentMonth());

        var eras = history.erasFor(entry.getId());
        assertEquals(2, eras.size(), "re-saving what the screen shows records nothing");
        assertEquals(0, new BigDecimal("100.00").compareTo(eras.get(1).getAmount()));
    }

    @Test
    void aCorrectionWithAPendingChangeAmendsTheEraInForceNow() {
        String nextMonth = YearMonth.now().plusMonths(1).toString();
        history.changeEntryAmountForward(entry, new BigDecimal("100.00"), nextMonth);

        // "This was always 235" is about the number on screen today — not the
        // pending September amount, which happens to be the LATEST era.
        history.correctEntryAmount(entry, new BigDecimal("235.00"));

        var eras = history.erasFor(entry.getId());
        assertEquals(0, new BigDecimal("235.00").compareTo(eras.get(0).getAmount()),
                "the era in force now was amended");
        assertEquals(0, new BigDecimal("100.00").compareTo(eras.get(1).getAmount()),
                "the pending change is untouched");
    }

    @Test
    void aGarbledEffectiveMonthIsRefused() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> history.changeEntryAmountForward(entry, new BigDecimal("100.00"), "2026-13"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> history.changeEntryAmountForward(entry, new BigDecimal("100.00"), "September"));
        assertTrue(history.erasFor(entry.getId()).isEmpty(), "a refused save writes nothing");
    }

    @Test
    void theAnnualBudgetMirrorsTheSameRules() {
        AppSettings settings = settingsRepo.findById(1L).orElseGet(() -> {
            AppSettings s = new AppSettings();
            s.setId(1L);
            return s;
        });
        settings.setAnnualBudget(new BigDecimal("1200.00"));
        settingsRepo.save(settings);

        history.changeAnnualBudgetForward(settings, new BigDecimal("2400.00"));

        var resolver = history.resolver();
        assertEquals(0, new BigDecimal("1200.00").compareTo(resolver.annual(monthBefore())));
        assertEquals(0, new BigDecimal("2400.00").compareTo(resolver.annual(history.currentMonth())));
        assertEquals(2, history.annualEras().size());
    }
}
