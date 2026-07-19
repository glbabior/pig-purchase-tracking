package com.pigpurchases.service;

import java.math.BigDecimal;
import java.time.YearMonth;

public class MonthlyHistoryEntry {
    private final YearMonth month;
    private final BigDecimal budget;
    private final BigDecimal spend;

    public MonthlyHistoryEntry(YearMonth month, BigDecimal budget, BigDecimal spend) {
        this.month = month;
        this.budget = budget;
        this.spend = spend;
    }

    public YearMonth getMonth() {
        return month;
    }

    public BigDecimal getBudget() {
        return budget;
    }

    public BigDecimal getSpend() {
        return spend;
    }
}
