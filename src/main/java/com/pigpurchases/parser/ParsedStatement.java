package com.pigpurchases.parser;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Result of parsing one statement file: the transactions plus the statement's
 * own printed summary totals, which are used to verify the parse reconciles.
 */
public class ParsedStatement {
    private final LocalDate closingDate;
    private final List<ParsedTransaction> transactions;
    private final BigDecimal summaryPurchases; // as printed: "Purchases +$X"
    private final BigDecimal summaryCredits;   // as printed: "Payment, Credits -$Y" (negative)

    public ParsedStatement(LocalDate closingDate, List<ParsedTransaction> transactions,
                           BigDecimal summaryPurchases, BigDecimal summaryCredits) {
        this.closingDate = closingDate;
        this.transactions = transactions;
        this.summaryPurchases = summaryPurchases;
        this.summaryCredits = summaryCredits;
    }

    public LocalDate getClosingDate() {
        return closingDate;
    }

    public List<ParsedTransaction> getTransactions() {
        return transactions;
    }

    public BigDecimal getSummaryPurchases() {
        return summaryPurchases;
    }

    public BigDecimal getSummaryCredits() {
        return summaryCredits;
    }
}
