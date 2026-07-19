package com.pigpurchases;

import com.pigpurchases.service.BudgetService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class BudgetServiceTest {
    @Test
    void calculatesBudgetSummary() throws Exception {
        Path tempFile = Files.createTempFile("budget", ".txt");
        BudgetService service = new BudgetService(tempFile);
        BudgetService.BudgetState state = new BudgetService.BudgetState();
        state.getBudgetEntries().add(new com.pigpurchases.model.BudgetEntry("Groceries", "Food", new BigDecimal("100")));
        state.getBudgetEntries().add(new com.pigpurchases.model.BudgetEntry("Auto Payment", "Auto", new BigDecimal("200")));

        BudgetService.BudgetSummary summary = service.calculateSummary(state, List.of(
                "Groceries $45.00",
                "Auto Payment $60.00"
        ));

        assertEquals(new BigDecimal("300"), summary.getTotalBudget());
        assertEquals(new BigDecimal("105.00"), summary.getTotalSpend());
        assertEquals(new BigDecimal("-195.00"), summary.getVariance());
        assertEquals(new BigDecimal("35.00"), summary.getPercentUsed());
    }
}
