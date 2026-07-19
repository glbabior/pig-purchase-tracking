package com.pigpurchases.service;

import com.pigpurchases.model.BudgetEntry;
import com.pigpurchases.model.PurchaseSource;
import com.pigpurchases.model.Settings;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

public class BudgetService {
    private final Path dataFile;

    public BudgetService(Path dataFile) {
        this.dataFile = dataFile;
    }

    public BudgetState loadState() throws Exception {
        if (dataFile == null || !Files.exists(dataFile)) {
            return new BudgetState();
        }
        String content = Files.readString(dataFile, StandardCharsets.UTF_8);
        if (content == null || content.isBlank()) {
            return new BudgetState();
        }
        BudgetState state = new BudgetState();
        state.getBudgetEntries().addAll(parseBudgetEntries(content));
        return state;
    }

    public void saveState(BudgetState state) throws Exception {
        Files.createDirectories(dataFile.getParent());
        StringBuilder builder = new StringBuilder();
        builder.append("# Pig Purchases\n");
        builder.append("budgetEntries=\n");
        for (BudgetEntry entry : state.getBudgetEntries()) {
            builder.append(String.format("%s|%s|%s\n", entry.getName(), entry.getCategory(), entry.getMonthlyAllowance()));
        }
        builder.append("ignoredEntries=\n");
        for (String ignored : state.getIgnoredEntries()) {
            builder.append(ignored).append('\n');
        }
        builder.append("purchaseSources=\n");
        for (PurchaseSource source : state.getPurchaseSources()) {
            builder.append(source.getName()).append('|').append(source.getType()).append('\n');
        }
        builder.append("settings=\n");
        builder.append(state.getSettings().getReminderDayOfMonth()).append('\n');
        Files.writeString(dataFile, builder.toString(), StandardCharsets.UTF_8);
    }

    private List<BudgetEntry> parseBudgetEntries(String content) {
        List<BudgetEntry> entries = new ArrayList<>();
        String[] lines = content.split("\\R");
        for (String line : lines) {
            if (line.startsWith("budgetEntries=")) {
                continue;
            }
            if (line.startsWith("ignoredEntries=") || line.startsWith("purchaseSources=") || line.startsWith("settings=")) {
                continue;
            }
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\|", 3);
            if (parts.length == 3) {
                entries.add(new BudgetEntry(parts[0], parts[1], new BigDecimal(parts[2])));
            }
        }
        return entries;
    }

    public List<BudgetEntry> buildEntriesFromStatements(List<String> statements) {
        List<BudgetEntry> entries = new ArrayList<>();
        for (String statement : statements) {
            String[] rows = statement.split("\\R");
            for (String row : rows) {
                if (row.contains("$")) {
                    String sanitized = row.replaceAll("[^a-zA-Z0-9.,-]", "").trim();
                    if (!sanitized.isBlank()) {
                        entries.add(new BudgetEntry(sanitized, "statement", new BigDecimal("0.00")));
                    }
                }
            }
        }
        return entries;
    }

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
        private final List<String> ignoredEntries = new ArrayList<>();
        private final List<PurchaseSource> purchaseSources = new ArrayList<>();
        private final Settings settings = new Settings();

        public List<BudgetEntry> getBudgetEntries() {
            return budgetEntries;
        }

        public List<String> getIgnoredEntries() {
            return ignoredEntries;
        }

        public List<PurchaseSource> getPurchaseSources() {
            return purchaseSources;
        }

        public Settings getSettings() {
            return settings;
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
