package com.pigpurchases.parser;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One transaction extracted from a statement. Amount is signed: purchases are
 * positive, payments/credits are negative (matching how the statement presents
 * them). description is the full activity text; vendor is a lightly-cleaned
 * merchant name for later categorization.
 */
public class ParsedTransaction {
    public enum Type { PURCHASE, PAYMENT, CREDIT, DEPOSIT, WITHDRAWAL, CHECK, FEE }

    private final LocalDate date;
    private final String description;
    private final String vendor;
    private final BigDecimal amount;
    private final Type type;
    private final boolean excludeFromSpend;
    private final String excludeReason;

    public ParsedTransaction(LocalDate date, String description, String vendor, BigDecimal amount, Type type) {
        this(date, description, vendor, amount, type, false, null);
    }

    public ParsedTransaction(LocalDate date, String description, String vendor, BigDecimal amount, Type type,
                             boolean excludeFromSpend, String excludeReason) {
        this.date = date;
        this.description = description;
        this.vendor = vendor;
        this.amount = amount;
        this.type = type;
        this.excludeFromSpend = excludeFromSpend;
        this.excludeReason = excludeReason;
    }

    public LocalDate getDate() {
        return date;
    }

    public String getDescription() {
        return description;
    }

    public String getVendor() {
        return vendor;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Type getType() {
        return type;
    }

    /** True when this transaction should not count toward budget spend (e.g. an inter-account transfer). */
    public boolean isExcludeFromSpend() {
        return excludeFromSpend;
    }

    public String getExcludeReason() {
        return excludeReason;
    }
}
