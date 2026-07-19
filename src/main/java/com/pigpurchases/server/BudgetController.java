package com.pigpurchases.server;

import com.pigpurchases.model.AppSettings;
import com.pigpurchases.model.BudgetEntry;
import com.pigpurchases.repository.AppSettingsRepository;
import com.pigpurchases.repository.BudgetEntryRepository;
import com.pigpurchases.repository.TransactionRepository;
import com.pigpurchases.service.BudgetService;
import com.pigpurchases.service.MonthlyHistoryEntry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class BudgetController {
    @Autowired
    private BudgetEntryRepository budgetEntryRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private AppSettingsRepository appSettingsRepository;

    @Autowired
    private BudgetService budgetService;

    private static final Long SETTINGS_ID = 1L;
    private static final BigDecimal MONTHS_PER_YEAR = BigDecimal.valueOf(12);

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    /**
     * Restarts the application so freshly recompiled code is picked up. Uses
     * Spring Boot DevTools' Restarter (active when launched via spring-boot:run),
     * which reloads changed classes via the restart classloader. The restart runs
     * on a separate non-daemon thread so this HTTP response can return first.
     */
    @PostMapping("/restart")
    public Map<String, String> restart() {
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            org.springframework.boot.devtools.restart.Restarter.getInstance().restart();
        });
        thread.setDaemon(false);
        thread.setName("app-restart");
        thread.start();
        return Map.of("status", "restarting");
    }

    @GetMapping("/settings")
    public Map<String, Object> getSettings() {
        return settingsResponse(loadOrCreateSettings());
    }

    @PutMapping("/settings")
    public Map<String, Object> updateSettings(@RequestBody Map<String, Object> payload) {
        AppSettings settings = loadOrCreateSettings();
        settings.setAnnualBudget(new BigDecimal(String.valueOf(payload.get("annualBudget"))));
        return settingsResponse(appSettingsRepository.save(settings));
    }

    private AppSettings loadOrCreateSettings() {
        return appSettingsRepository.findById(SETTINGS_ID).orElseGet(() -> {
            AppSettings created = new AppSettings();
            created.setId(SETTINGS_ID);
            created.setAnnualBudget(BigDecimal.ZERO);
            return appSettingsRepository.save(created);
        });
    }

    private Map<String, Object> settingsResponse(AppSettings settings) {
        BigDecimal annual = settings.getAnnualBudget() != null ? settings.getAnnualBudget() : BigDecimal.ZERO;
        BigDecimal monthly = annual.divide(MONTHS_PER_YEAR, 2, RoundingMode.HALF_UP);
        Map<String, Object> response = new HashMap<>();
        response.put("annualBudget", annual.toPlainString());
        response.put("monthlyAllowance", monthly.toPlainString());
        return response;
    }

    @GetMapping("/entries")
    public List<Map<String, Object>> getEntries() {
        List<BudgetEntry> entries = budgetEntryRepository.findAll();
        List<Map<String, Object>> result = new ArrayList<>();
        for (BudgetEntry entry : entries) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", entry.getId());
            map.put("title", entry.getName());
            map.put("quantity", entry.getQuantity() != null ? entry.getQuantity() : 1);
            map.put("monthlyBudget", entry.getMonthlyAllowance().toPlainString());
            map.put("hints", entry.getHints() != null ? entry.getHints() : "");
            result.add(map);
        }
        return result;
    }

    @PostMapping("/entries")
    public BudgetEntry createEntry(@RequestBody Map<String, Object> payload) {
        BudgetEntry entry = new BudgetEntry();
        entry.setName((String) payload.get("title"));
        entry.setMonthlyAllowance(new BigDecimal(String.valueOf(payload.get("monthlyBudget"))));
        entry.setQuantity(((Number) payload.getOrDefault("quantity", 1)).intValue());
        entry.setHints((String) payload.getOrDefault("hints", ""));
        return budgetEntryRepository.save(entry);
    }

    @PutMapping("/entries/{id}")
    public BudgetEntry updateEntry(@PathVariable Long id, @RequestBody Map<String, Object> payload) {
        BudgetEntry entry = budgetEntryRepository.findById(id).orElseThrow();
        entry.setName((String) payload.get("title"));
        entry.setMonthlyAllowance(new BigDecimal(String.valueOf(payload.get("monthlyBudget"))));
        entry.setQuantity(((Number) payload.getOrDefault("quantity", 1)).intValue());
        entry.setHints((String) payload.getOrDefault("hints", ""));
        return budgetEntryRepository.save(entry);
    }

    @DeleteMapping("/entries/{id}")
    public void deleteEntry(@PathVariable Long id) {
        budgetEntryRepository.deleteById(id);
    }

    @PostMapping("/summary")
    public Map<String, Object> summary(@RequestBody Map<String, Object> payload) {
        BudgetService.BudgetState state = new BudgetService.BudgetState();
        List<Map<String, Object>> entries = (List<Map<String, Object>>) payload.get("entries");
        for (Map<String, Object> entry : entries) {
            state.getBudgetEntries().add(new BudgetEntry(
                    (String) entry.get("name"),
                    new BigDecimal(String.valueOf(entry.get("allowance")))
            ));
        }
        List<String> statements = new ArrayList<>();
        for (Object item : (List<Object>) payload.get("statements")) {
            statements.add(String.valueOf(item));
        }
        BudgetService.BudgetSummary summary = budgetService.calculateSummary(state, statements);
        List<MonthlyHistoryEntry> history = new ArrayList<>();
        history.add(new MonthlyHistoryEntry(YearMonth.now(), summary.getTotalBudget(), summary.getTotalSpend()));
        BudgetService.RollingAverageSummary rolling = budgetService.calculateRollingAverage(history);

        Map<String, Object> response = new HashMap<>();
        response.put("totalBudget", summary.getTotalBudget());
        response.put("totalSpend", summary.getTotalSpend());
        response.put("variance", summary.getVariance());
        response.put("percentUsed", summary.getPercentUsed());
        response.put("rollingAverageBudget", rolling.getAverageBudget());
        response.put("rollingAverageSpend", rolling.getAverageSpend());
        return response;
    }
}

