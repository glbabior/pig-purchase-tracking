package com.pigpurchases.parser;

/**
 * A per-source rule that flags matching transactions as excluded from budget
 * spend (kept in the data, but not counted). {@code contains} is matched
 * case-insensitively against the transaction description.
 */
public record ExclusionRule(String contains, String reason) {

    public boolean matches(String description) {
        return description != null && contains != null
                && description.toLowerCase().contains(contains.toLowerCase());
    }
}
