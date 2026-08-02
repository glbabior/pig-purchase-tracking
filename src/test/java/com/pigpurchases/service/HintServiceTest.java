package com.pigpurchases.service;

import com.pigpurchases.model.BudgetEntry;
import com.pigpurchases.model.Transaction;
import com.pigpurchases.repository.BudgetEntryRepository;
import com.pigpurchases.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Hints screen's logic. Each of these answers a question the app previously could not:
 * which rules are ignored, which rules fight each other, and what a rule would catch before
 * you commit to it.
 */
@SpringBootTest
@ActiveProfiles("test")
class HintServiceTest {

    @Autowired private HintService hintService;
    @Autowired private BudgetEntryRepository entryRepo;
    @Autowired private TransactionRepository txnRepo;

    @BeforeEach
    void reset() {
        txnRepo.deleteAll();
        entryRepo.deleteAll();
    }

    private BudgetEntry entry(String name, String hints) {
        BudgetEntry e = new BudgetEntry(name, new BigDecimal("50.00"));
        e.setHints(hints);
        return entryRepo.save(e);
    }

    private void txn(String description) {
        Transaction t = new Transaction(LocalDate.of(2026, 6, 15), description, description,
                new BigDecimal("10.00"), "2026-06");
        t.setType("PURCHASE");
        txnRepo.save(t);
    }

    // ---- validate: why a rule does nothing --------------------------------

    @Test
    void anIgnoredRuleSaysWhyRatherThanVanishing() {
        // The exact shape that silently stopped working: a composite whose part is too short
        // is discarded whole, which is right — but it used to happen with no explanation.
        HintMatcher.HintProblem problem = HintMatcher.validate("T + MOBILE");
        assertNotNull(problem, "a one-character composite part must be reported");
        assertTrue(problem.problem().contains("T"), problem.problem());

        assertNotNull(HintMatcher.validate("ab"), "a bare two-character rule matches too much");
        assertNull(HintMatcher.validate("DAILYGRIND"));
        assertNull(HintMatcher.validate("MP + MAILORDER"), "a valid composite must pass");
    }

    @Test
    void theHintListReportsIgnoredRulesAndHowMuchEachMatches() {
        entry("Phone", "This is my phone bill\nmatch: T + MOBILE\nmatch: NOVACELL");
        txn("NOVACELL PCS SVC 800-555-0188");
        txn("NOVACELL AUTOPAY THANK YOU");
        txn("DAILYGRIND STORE 1234");

        List<HintService.HintRow> rows = hintService.allHints();
        assertEquals(2, rows.size(), "prose lines are not rules and must not be listed");

        HintService.HintRow broken = rows.stream()
                .filter(r -> r.hint().equals("T + MOBILE")).findFirst().orElseThrow();
        assertNotNull(broken.problem(), "the ignored rule must carry its reason");
        assertEquals(0, broken.matchCount(), "an ignored rule matches nothing by definition");

        HintService.HintRow working = rows.stream()
                .filter(r -> r.hint().equals("NOVACELL")).findFirst().orElseThrow();
        assertNull(working.problem());
        assertEquals(2, working.matchCount(), "both Novacell rows, not the Daily Grind one");
    }

    // ---- conflicts: rules that fight ---------------------------------------

    @Test
    void aTieBetweenCategoriesIsReportedAsParkingTheTransaction() {
        // Equal weights on different entries: doMap refuses to guess and parks the row, so
        // both rules look broken and neither user-written rule does anything.
        entry("Dining", "match: BREWHOUSE");
        entry("Coffee", "match: COFFEEBAR");
        txn("BREWHOUSE COFFEEBAR RIVERTON");

        List<HintService.Conflict> conflicts = hintService.conflicts();
        assertEquals(1, conflicts.size());
        assertTrue(conflicts.get(0).parks(),
                "equal-weight rules on different categories park the transaction");
        assertEquals(2, conflicts.get(0).claims().size());
    }

    @Test
    void anUnevenOverlapIsReportedButDoesNotPark() {
        // The quieter case: the longer rule wins silently and the other never fires, which
        // is invisible from any screen that only shows the outcome.
        entry("Harbor Park", "match: HARBOR PARK");
        entry("Harbor Park tickets", "match: HARBORPARKPASS");
        txn("HARBORPARKPASS RIVERTON CA");

        List<HintService.Conflict> conflicts = hintService.conflicts();
        assertEquals(1, conflicts.size());
        assertFalse(conflicts.get(0).parks(), "different weights resolve, they do not park");
        assertTrue(conflicts.get(0).claims().stream().anyMatch(c -> c.contains("loses")),
                "the losing rule must be identified: " + conflicts.get(0).claims());
    }

    @Test
    void twoRulesOnTheSameCategoryAreNotAConflict() {
        // Having several rules for one category is the whole point; both matching is fine.
        entry("Coffee", "match: DAILYGRIND\nmatch: STORE");
        txn("DAILYGRIND STORE 1234");

        assertTrue(hintService.conflicts().isEmpty(),
                "an overlap within one category costs nothing and must not be reported");
    }

    // ---- preview: what a rule would catch ----------------------------------

    @Test
    void previewShowsTheBlastRadiusBeforeSaving() {
        BudgetEntry coffee = entry("Coffee", null);
        txn("DAILYGRIND STORE 1234 RIVERTON CA");
        txn("DAILYGRIND STORE 9876 RIVERTON CA");
        txn("GREENGROCER 0123 RIVERTON CA");

        HintService.Preview p = hintService.preview(coffee.getId(), "DAILYGRIND");
        assertNull(p.problem());
        assertEquals(2, p.matchCount(), "both Daily Grind rows, whatever the store number");
        assertEquals(2, p.samples().size());
        assertTrue(p.alreadyElsewhere().isEmpty(), "nothing else claims them");
    }

    @Test
    void previewWarnsWhenTheRuleWouldStealFromAnotherCategory() {
        BudgetEntry groceries = entry("Groceries", "match: GREENGROCER");
        BudgetEntry dining = entry("Dining", null);
        txn("GREENGROCER 0123 RIVERTON CA");

        HintService.Preview p = hintService.preview(dining.getId(), "GREENGROCER 0123");
        assertEquals(1, p.matchCount());
        assertEquals(1, p.alreadyElsewhere().size(),
                "a rule that takes a transaction from another category must say so");
        assertTrue(p.alreadyElsewhere().get(0).contains("Groceries"), p.alreadyElsewhere().toString());
        assertNotNull(groceries.getId());
    }

    @Test
    void previewRefusesToPretendAnUnusableRuleWillWork() {
        BudgetEntry phone = entry("Phone", null);
        txn("NOVACELL PCS SVC");

        HintService.Preview p = hintService.preview(phone.getId(), "T + MOBILE");
        assertNotNull(p.problem(), "an unusable rule must be reported, not previewed as working");
        assertEquals(0, p.matchCount());
    }
}
