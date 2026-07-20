package com.pigpurchases.parser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExclusionRuleTest {

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
