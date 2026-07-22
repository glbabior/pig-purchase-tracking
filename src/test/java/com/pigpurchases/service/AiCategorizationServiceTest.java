package com.pigpurchases.service;

import com.pigpurchases.model.BudgetEntry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the privacy boundary and the request shape. Nothing here calls the API —
 * these assert what {@code promptFor} puts on the wire, which is the whole point:
 * the claim "amounts never leave the machine" should be enforced by a test, not
 * by a comment.
 */
class AiCategorizationServiceTest {

    private static BudgetEntry entry(long id, String name, String hints) {
        BudgetEntry e = new BudgetEntry(name, new BigDecimal("123.45"));
        e.setId(id);
        e.setHints(hints);
        return e;
    }

    private static final List<BudgetEntry> ENTRIES = List.of(
            entry(1, "Groceries", "Fresh Market and Fresh Market runs"),
            entry(2, "Gas", "This is gasoline for my car"));

    @Test
    void promptCarriesDescriptionsAndHintsButNeverAmounts() {
        List<AiCategorizationService.Candidate> batch = List.of(
                new AiCategorizationService.Candidate("freshmarketwhse", "FRESHMARKET WHSE #1234 RIVERTON CA", "FRESHMARKET WHSE"),
                new AiCategorizationService.Candidate("tstjoes", "TST* PIZZA NIGHT", "TST* PIZZA NIGHT"));

        String prompt = AiCategorizationService.promptFor(batch, ENTRIES);

        // What must be present for the model to do the job.
        assertTrue(prompt.contains("FRESHMARKET WHSE #1234 RIVERTON CA"), prompt);
        assertTrue(prompt.contains("TST* PIZZA NIGHT"), prompt);
        assertTrue(prompt.contains("Groceries"), prompt);
        assertTrue(prompt.contains("Fresh Market and Fresh Market runs"), "hints must be sent — they are the knowledge base");
        assertTrue(prompt.contains("id=1"), "entry ids let the reply be matched back");

        // The privacy boundary: the budget entries' own monthly amounts must not leak.
        assertFalse(prompt.contains("123.45"), "budget amounts must never be sent:\n" + prompt);
    }

    @Test
    void promptContainsNoCurrencyAtAll() {
        // A transaction description could itself contain a stray number, but nothing
        // the service adds may look like money.
        String prompt = AiCategorizationService.promptFor(
                List.of(new AiCategorizationService.Candidate("k", "SOME MERCHANT", "SOME MERCHANT")),
                ENTRIES);
        assertFalse(prompt.contains("$"), "no dollar figures anywhere:\n" + prompt);
    }

    @Test
    void repeatedMerchantsCollapseToOneCandidate() {
        // Five Fresh Market visits should cost one line in the request, not five.
        List<AiCategorizationService.Candidate> candidates = List.of(
                new AiCategorizationService.Candidate("freshmarket", "FRESHMARKET WHSE #1", "FRESHMARKET"),
                new AiCategorizationService.Candidate("freshmarket", "FRESHMARKET WHSE #2", "FRESHMARKET"),
                new AiCategorizationService.Candidate("dailygrind", "DAILYGRIND STORE 42", "DAILYGRIND"),
                new AiCategorizationService.Candidate("freshmarket", "FRESHMARKET WHSE #3", "FRESHMARKET"));

        List<AiCategorizationService.Candidate> deduped = AiCategorizationService.dedupe(candidates);

        assertEquals(2, deduped.size());
        assertEquals("freshmarket", deduped.get(0).key(), "first occurrence wins, order is stable");
        assertEquals("dailygrind", deduped.get(1).key());
    }

    @Test
    void vendorIsOmittedWhenItAddsNothing() {
        String prompt = AiCategorizationService.promptFor(
                List.of(new AiCategorizationService.Candidate("k", "SAME TEXT", "SAME TEXT")),
                ENTRIES);
        assertFalse(prompt.contains("(vendor:"), "no point repeating an identical vendor:\n" + prompt);
    }
}
