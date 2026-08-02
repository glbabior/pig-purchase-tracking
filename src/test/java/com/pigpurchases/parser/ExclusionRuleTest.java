package com.pigpurchases.parser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExclusionRuleTest {

    /**
     * "anything".contains("") is true, so a rules entry with the key misspelled or missing
     * excluded every transaction from that source — the account contributed zero to every
     * month while the ingest reported success and reconciliation still passed, since it
     * sums parsed rows regardless of the exclusion flag. Parser rules are hand-authored
     * during source setup, which is exactly where a typo lands.
     */
    @Test
    void aBlankPatternMatchesNothingRatherThanEverything() {
        assertFalse(new ExclusionRule("", "typo").matches("COFFEE SHOP ANYTOWN CA"));
        assertFalse(new ExclusionRule("   ", "whitespace").matches("COFFEE SHOP ANYTOWN CA"));
        assertFalse(new ExclusionRule(null, "missing").matches("COFFEE SHOP ANYTOWN CA"));
    }

    @Test
    void matchesCaseInsensitiveSubstring() {
        ExclusionRule rule = new ExclusionRule("Ridgeline", "rent");
        assertTrue(rule.matches("PL*RidgelinePr DES:WEB PMTS"));
        assertTrue(rule.matches("pl*ridgelinepr des:web pmts"));
        assertFalse(rule.matches("SOME OTHER VENDOR"));
    }

    @Test
    void handlesNulls() {
        assertFalse(new ExclusionRule("X", "r").matches(null));
        assertFalse(new ExclusionRule(null, "r").matches("anything"));
    }
}
