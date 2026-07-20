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

    public ParsedTransaction(LocalDate date, String description, String vendor, BigDecimal amount, Type type) {
        this.date = date;
        this.description = description;
        this.vendor = vendor;
        this.amount = amount;
        this.type = type;
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
}
