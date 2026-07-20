package com.pigpurchases.parser;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Result of parsing one statement file: the transactions plus named "control
 * totals" printed on the statement, used to verify the parse reconciles. Each
 * issuer supplies whatever totals it prints (e.g. Crestline: purchases/credits;
 * Bayside: beginningBalance/endingBalance/deposits/otherSubtractions).
 */
public class ParsedStatement {
    private final LocalDate statementDate;
    private final List<ParsedTransaction> transactions;
    private final Map<String, BigDecimal> controlTotals;

    public ParsedStatement(LocalDate statementDate, List<ParsedTransaction> transactions,
                           Map<String, BigDecimal> controlTotals) {
        this.statementDate = statementDate;
        this.transactions = transactions;
        this.controlTotals = controlTotals;
    }

    public LocalDate getStatementDate() {
        return statementDate;
    }

    public List<ParsedTransaction> getTransactions() {
        return transactions;
    }

    public Map<String, BigDecimal> getControlTotals() {
        return controlTotals;
    }

    public BigDecimal control(String key) {
        return controlTotals.get(key);
    }
}
