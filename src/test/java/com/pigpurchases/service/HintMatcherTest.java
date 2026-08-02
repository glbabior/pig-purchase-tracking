package com.pigpurchases.service;

import com.pigpurchases.model.BudgetEntry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deterministic first pass. These cases come from the real budget entry
 * names and real statement descriptions, which is where the normalization rules
 * were derived from.
 */
class HintMatcherTest {

    private static BudgetEntry entry(long id, String name, String hints) {
        BudgetEntry e = new BudgetEntry(name, new BigDecimal("10.00"));
        e.setId(id);
        e.setHints(hints);
        return e;
    }

    @Test
    void matchesAcrossPunctuationAndSpacing() {
        HintMatcher matcher = new HintMatcher(List.of(
                entry(1, "City Power", null),
                entry(2, "Novacell", null),
                entry(3, "Daily Grind", null)));

        assertEquals("City Power", matcher.match("CITYPOWER 800-555-0142 CA", "CITYPOWER").orElseThrow().entry().getName());
        assertEquals("Novacell", matcher.match("NOVA-CELL PCS SVC", "NOVA-CELL").orElseThrow().entry().getName());
        assertEquals("Daily Grind", matcher.match("DAILYGRIND*COFFEE", "DAILYGRIND").orElseThrow().entry().getName());
    }

    /**
     * A composite whose part is too short used to lose that part and register as a bare
     * substring, so "T + MOBILE" quietly became `mobile` and matched anything containing
     * it. An AND the user wrote must never become an OR-of-one — the whole hint is
     * discarded instead, and the transaction parks for review.
     */
    @Test
    void aCompositeWithATooShortPartIsDiscardedRatherThanBroadened() {
        HintMatcher phone = new HintMatcher(List.of(entry(1, "Phone bill", "match: T + MOBILE")));
        assertTrue(phone.match("MOBILE DEPOSIT 12345", "MOBILE DEPOSIT").isEmpty(),
                "a deposit is not a phone bill");
        assertTrue(phone.match("UNITED MOBILE INC", "UNITED MOBILE").isEmpty());

        HintMatcher store = new HintMatcher(List.of(entry(1, "Corner store", "match: 7 + ELEVEN")));
        assertTrue(store.match("CORNER BISTRO PARK", "CORNER BISTRO").isEmpty(),
                "a restaurant is not a convenience store");

        // Composites whose parts are all long enough are untouched — this is the documented
        // way to pair an ambiguous token with a distinctive one.
        HintMatcher rx = new HintMatcher(List.of(entry(1, "Pharmacy", "match: MP + MAILORDER")));
        assertEquals("Pharmacy", rx.match("MP RX00042 MAILORDER80 800-555-0175 CA", "KP RX")
                .orElseThrow().entry().getName());
    }

    @Test
    void ignoresPatternsTooShortToTrust() {
        // "Gas" would otherwise match CITYPOWER and POWELL ST GARAGE.
        HintMatcher matcher = new HintMatcher(List.of(entry(1, "Gas", null)));
        assertTrue(matcher.match("CITYPOWER 800-555-0142", "CITYPOWER").isEmpty());
        assertTrue(matcher.match("POWELL ST GARAGE CA", "HOTEL").isEmpty());
    }

    @Test
    void longestPatternWinsAsMostSpecific() {
        HintMatcher matcher = new HintMatcher(List.of(
                entry(1, "Harbor Park visit", null),
                entry(2, "Harbor Park Pass", null)));
        Optional<HintMatcher.Match> match = matcher.match("HARBOR PARK PASS SALES RIVERTON CA", "HARBOR PARK PASS");
        assertEquals("Harbor Park Pass", match.orElseThrow().entry().getName());
    }

    @Test
    void equallySpecificRivalsAreParkedRatherThanGuessed() {
        HintMatcher matcher = new HintMatcher(List.of(
                entry(1, "Puppy food", "match: PETMART"),
                entry(2, "Kid gifts", "match: PETMART")));
        assertTrue(matcher.match("PETMART.COM 800-555-0163", "PETMART").isEmpty(),
                "two entries claim the same pattern; guessing would be worse than parking");
    }

    @Test
    void proseHintsDoNotMatchButExplicitOnesDo() {
        BudgetEntry roadster = entry(1, "Roadster", "This is my auto loan payment\nmatch: MERIDIAN MOTORS");
        HintMatcher matcher = new HintMatcher(List.of(roadster));

        // The prose is for the AI pass; it must not be substring-matched.
        assertTrue(matcher.match("PAYMENT TO SOMEONE", "PAYMENT").isEmpty());
        assertEquals("Roadster",
                matcher.match("MERIDIAN MOTORS FINANCE", "MERIDIAN").orElseThrow().entry().getName());
        // The entry name still works on its own.
        assertEquals("Roadster", matcher.match("ROADSTER SERVICE", "ROADSTER").orElseThrow().entry().getName());
    }

    @Test
    void explicitHintsAreParsedFromMatchLinesOnly() {
        assertEquals(List.of("MERIDIAN", "CRESTLINE AUTO"),
                HintMatcher.explicitHints("Prose line\nmatch: MERIDIAN\n  match:CRESTLINE AUTO  \nmore prose"));
        assertTrue(HintMatcher.explicitHints("just prose").isEmpty());
        assertTrue(HintMatcher.explicitHints(null).isEmpty());
    }

    @Test
    void shortExplicitHintsAreAllowed() {
        // "HPK" (3 chars) is too short as an entry NAME but fine as a deliberate hint.
        HintMatcher matcher = new HintMatcher(List.of(entry(1, "Harbor Park visit", "match: HPK")));
        assertEquals("Harbor Park visit",
                matcher.match("HPK ROSEWOOD TAVERN 800-555-0134 CA", "HPK ROSEWOOD TAVERN").orElseThrow().entry().getName());
    }

    @Test
    void compositeHintRequiresAllSubstrings() {
        // "hpk + monthly" targets only the annual-pass line, leaving other HPK charges to the broad hint.
        HintMatcher matcher = new HintMatcher(List.of(
                entry(1, "Harbor Park visit", "match: HPK"),
                entry(2, "Harbor Park Pass", "match: HPK + monthly")));

        // The monthly payment contains both "hpk" and "monthly" -> the composite wins (higher weight).
        assertEquals("Harbor Park Pass",
                matcher.match("HPK AP Monthly Payment 800-555-0157 CA", "HPK AP Monthly Payment").orElseThrow().entry().getName());
        // A regular HPK charge lacks "monthly" -> only the broad hint matches.
        assertEquals("Harbor Park visit",
                matcher.match("HPK PARKING RIVERTON CA", "HPK PARKING").orElseThrow().entry().getName());
    }

    @Test
    void compositeHintAllowsShortDiscriminatorPairedWithSpecificOne() {
        // Real case: a mail-order pharmacy copay. "KP" alone is too short to trust,
        // but "MP + MAILORDER" is safe because both must appear.
        HintMatcher matcher = new HintMatcher(List.of(entry(1, "Pharmacy", "match: MP + MAILORDER")));
        assertEquals("Pharmacy",
                matcher.match("MP RX00042 MAILORDER80 800-555-0175 CA", "MP RX00042").orElseThrow().entry().getName());
        // The short part on its own must not match — the specific part has to be there too.
        assertTrue(matcher.match("MP FITNESS CENTER RIVERTON CA", "MP FITNESS").isEmpty());
    }

    @Test
    void unmatchedTransactionsAreEmpty() {
        HintMatcher matcher = new HintMatcher(List.of(entry(1, "Groceries", null)));
        assertTrue(matcher.match("SOME UNKNOWN VENDOR LLC", "SOME UNKNOWN").isEmpty());
    }
}
