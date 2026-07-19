package com.pigpurchases.service;

import com.pigpurchases.model.BudgetEntry;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure budget/spend calculations. No persistence lives here anymore — the H2
 * database (via the JPA repositories) is the single source of truth. This is a
 * Spring-managed bean so controllers receive it by injection.
 */
@Service
public class BudgetService {

    public BudgetSummary calculateSummary(BudgetState state, List<String> statements) {
        BigDecimal totalBudget = state.getBudgetEntries().stream()
                .map(BudgetEntry::getMonthlyAllowance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal spend = BigDecimal.ZERO;
        for (String statement : statements) {
            for (String row : statement.split("\\R")) {
                if (row.contains("$")) {
                    String amountText = row.replaceAll("[^0-9.\\-]", "");
                    if (!amountText.isBlank()) {
                        spend = spend.add(new BigDecimal(amountText));
                    }
                }
            }
        }

        BigDecimal variance = spend.subtract(totalBudget);
        BigDecimal percentUsed = totalBudget.signum() == 0 ? BigDecimal.ZERO : spend.multiply(BigDecimal.valueOf(100)).divide(totalBudget, 2, RoundingMode.HALF_UP);
        return new BudgetSummary(totalBudget, spend, variance, percentUsed);
    }

    public RollingAverageSummary calculateRollingAverage(List<MonthlyHistoryEntry> history) {
        if (history.isEmpty()) {
            return new RollingAverageSummary(BigDecimal.ZERO, BigDecimal.ZERO);
        }

        BigDecimal totalBudget = history.stream().map(MonthlyHistoryEntry::getBudget).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalSpend = history.stream().map(MonthlyHistoryEntry::getSpend).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal averageBudget = totalBudget.divide(BigDecimal.valueOf(history.size()), 2, RoundingMode.HALF_UP);
        BigDecimal averageSpend = totalSpend.divide(BigDecimal.valueOf(history.size()), 2, RoundingMode.HALF_UP);
        return new RollingAverageSummary(averageBudget, averageSpend);
    }

    public static class BudgetState {
        private final List<BudgetEntry> budgetEntries = new ArrayList<>();

        public List<BudgetEntry> getBudgetEntries() {
            return budgetEntries;
        }
    }

    public static class BudgetSummary {
        private final BigDecimal totalBudget;
        private final BigDecimal totalSpend;
        private final BigDecimal variance;
        private final BigDecimal percentUsed;

        public BudgetSummary(BigDecimal totalBudget, BigDecimal totalSpend, BigDecimal variance, BigDecimal percentUsed) {
            this.totalBudget = totalBudget;
            this.totalSpend = totalSpend;
            this.variance = variance;
            this.percentUsed = percentUsed;
        }

        public BigDecimal getTotalBudget() {
            return totalBudget;
        }

        public BigDecimal getTotalSpend() {
            return totalSpend;
        }

        public BigDecimal getVariance() {
            return variance;
        }

        public BigDecimal getPercentUsed() {
            return percentUsed;
        }
    }

    public static class RollingAverageSummary {
        private final BigDecimal averageBudget;
        private final BigDecimal averageSpend;

        public RollingAverageSummary(BigDecimal averageBudget, BigDecimal averageSpend) {
            this.averageBudget = averageBudget;
            this.averageSpend = averageSpend;
        }

        public BigDecimal getAverageBudget() {
            return averageBudget;
        }

        public BigDecimal getAverageSpend() {
            return averageSpend;
        }
    }
}
