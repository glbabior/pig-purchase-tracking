package com.pigpurchases.parser;

/**
 * A per-source rule that flags matching transactions as excluded from budget
 * spend (kept in the data, but not counted). {@code contains} is matched
 * case-insensitively against the transaction description.
 */
public record ExclusionRule(String contains, String reason) {

    public boolean matches(String description) {
        // A blank pattern matches NOTHING, not everything. "anything".contains("") is true,
        // so a rules entry with the key misspelled or missing — parser rules are hand-authored
        // during source setup, which is where a typo lands — silently excluded every
        // transaction from that source, and the account contributed zero to every month with
        // the ingest still reporting success.
        return description != null && contains != null && !contains.isBlank()
                && description.toLowerCase().contains(contains.toLowerCase());
    }
}
