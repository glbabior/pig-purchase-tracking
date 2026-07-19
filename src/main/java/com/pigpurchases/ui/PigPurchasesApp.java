package com.pigpurchases.ui;

import com.pigpurchases.model.BudgetEntry;
import com.pigpurchases.model.PurchaseSource;
import com.pigpurchases.service.BudgetService;
import com.pigpurchases.service.MonthlyHistoryEntry;

import javax.swing.*;
import java.awt.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

public class PigPurchasesApp extends JFrame {
    private final BudgetService budgetService;
    private final BudgetService.BudgetState state;
    private final JTextArea budgetArea = new JTextArea();
    private final JTextArea ignoredArea = new JTextArea();
    private final JTextArea sourcesArea = new JTextArea();
    private final JTextField reminderField = new JTextField("1");
    private final JTextArea statementsArea = new JTextArea();
    private final JTextArea outputArea = new JTextArea();

    public PigPurchasesApp() throws Exception {
        super("Pig Purchases");
        this.budgetService = new BudgetService(Path.of("pig-purchases-data.txt"));
        this.state = budgetService.loadState();
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1100, 800);
        setLocationRelativeTo(null);
        buildUi();
        populateState();
    }

    private void populateState() {
        for (BudgetEntry entry : state.getBudgetEntries()) {
            budgetArea.append(entry.getName() + "|" + entry.getCategory() + "|" + entry.getMonthlyAllowance() + "\n");
        }
        for (String ignored : state.getIgnoredEntries()) {
            ignoredArea.append(ignored + "\n");
        }
        for (PurchaseSource source : state.getPurchaseSources()) {
            sourcesArea.append(source.getName() + "|" + source.getType() + "\n");
        }
        reminderField.setText(String.valueOf(state.getSettings().getReminderDayOfMonth()));
    }

    private void buildUi() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Configuration", createConfigurationPanel());
        tabs.addTab("Statements", createStatementsPanel());
        tabs.addTab("Reports", createReportsPanel());
        add(tabs, BorderLayout.CENTER);
    }

    private JPanel createConfigurationPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel formPanel = new JPanel(new GridLayout(0, 2, 8, 8));
        formPanel.add(new JLabel("Budget entries (name|category|allowance):"));
        formPanel.add(new JScrollPane(budgetArea));
        formPanel.add(new JLabel("Ignored entries:"));
        formPanel.add(new JScrollPane(ignoredArea));
        formPanel.add(new JLabel("Purchase sources (name|type):"));
        formPanel.add(new JScrollPane(sourcesArea));
        formPanel.add(new JLabel("Reminder day of month:"));
        formPanel.add(reminderField);

        JPanel buttonPanel = new JPanel();
        JButton saveButton = new JButton("Save configuration");
        saveButton.addActionListener(e -> saveConfiguration());
        buttonPanel.add(saveButton);

        panel.add(formPanel, BorderLayout.CENTER);
        panel.add(buttonPanel, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createStatementsPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JLabel instructions = new JLabel("Paste bank/credit card statement rows here, one per line.");
        panel.add(instructions, BorderLayout.NORTH);
        panel.add(new JScrollPane(statementsArea), BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel();
        JButton calculateButton = new JButton("Calculate spend");
        calculateButton.addActionListener(e -> calculateSpend());
        buttonPanel.add(calculateButton);
        panel.add(buttonPanel, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createReportsPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        outputArea.setEditable(false);
        panel.add(new JScrollPane(outputArea), BorderLayout.CENTER);
        return panel;
    }

    private void saveConfiguration() {
        state.getBudgetEntries().clear();
        state.getIgnoredEntries().clear();
        state.getPurchaseSources().clear();

        for (String line : budgetArea.getText().split("\\R")) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\\|", 3);
            if (parts.length == 3) {
                state.getBudgetEntries().add(new BudgetEntry(parts[0], parts[1], new BigDecimal(parts[2])));
            }
        }

        for (String line : ignoredArea.getText().split("\\R")) {
            if (!line.isBlank()) state.getIgnoredEntries().add(line.trim());
        }

        for (String line : sourcesArea.getText().split("\\R")) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\\|", 2);
            if (parts.length == 2) {
                state.getPurchaseSources().add(new PurchaseSource(parts[0], parts[1]));
            }
        }

        try {
            state.getSettings().setReminderDayOfMonth(Integer.parseInt(reminderField.getText()));
            budgetService.saveState(state);
            JOptionPane.showMessageDialog(this, "Configuration saved locally.");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Unable to save configuration: " + ex.getMessage());
        }
    }

    private void calculateSpend() {
        try {
            List<String> statements = List.of(statementsArea.getText().split("\\R"));
            BudgetService.BudgetSummary summary = budgetService.calculateSummary(state, statements);
            List<MonthlyHistoryEntry> history = new ArrayList<>();
            history.add(new MonthlyHistoryEntry(YearMonth.now(), summary.getTotalBudget(), summary.getTotalSpend()));
            BudgetService.RollingAverageSummary rollingAverage = budgetService.calculateRollingAverage(history);

            StringBuilder report = new StringBuilder();
            report.append("Monthly budget total: ").append(summary.getTotalBudget()).append("\n");
            report.append("Monthly spend total: ").append(summary.getTotalSpend()).append("\n");
            report.append("Variance: ").append(summary.getVariance()).append("\n");
            report.append("Percent used: ").append(summary.getPercentUsed()).append("%\n");
            report.append("Rolling average budget: ").append(rollingAverage.getAverageBudget()).append("\n");
            report.append("Rolling average spend: ").append(rollingAverage.getAverageSpend()).append("\n\n");
            report.append("Graph view:\n");
            report.append("Budget: ").append("#".repeat(Math.max(1, summary.getTotalBudget().intValueExact() / 10))).append("\n");
            report.append("Spend:  ").append("#".repeat(Math.max(1, summary.getTotalSpend().intValueExact() / 10))).append("\n\n");
            report.append("Budget entries:\n");
            for (BudgetEntry entry : state.getBudgetEntries()) {
                report.append("- ").append(entry.getName()).append(" (budget ").append(entry.getMonthlyAllowance()).append(")\n");
            }
            outputArea.setText(report.toString());
        } catch (Exception ex) {
            outputArea.setText("Unable to calculate spend: " + ex.getMessage());
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                new PigPurchasesApp().setVisible(true);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
    }
}
