package com.pigpurchases.server;

import com.pigpurchases.model.AppSettings;
import com.pigpurchases.model.BudgetEntry;
import com.pigpurchases.model.StatementSource;
import com.pigpurchases.repository.AppSettingsRepository;
import com.pigpurchases.repository.BudgetEntryRepository;
import com.pigpurchases.repository.StatementSourceRepository;
import com.pigpurchases.repository.TransactionRepository;
import com.pigpurchases.service.BudgetService;
import com.pigpurchases.service.MonthlyHistoryEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private StatementSourceRepository statementSourceRepository;

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

    // ---- Statement sources -------------------------------------------------
    // parserRules is stored per source but intentionally never returned to the
    // UI, and is preserved across updates (the UI only edits name + folderPath).

    @GetMapping("/statement-sources")
    public List<Map<String, Object>> getStatementSources() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (StatementSource source : statementSourceRepository.findAll()) {
            result.add(statementSourceResponse(source));
        }
        return result;
    }

    @PostMapping("/statement-sources")
    public Map<String, Object> createStatementSource(@RequestBody Map<String, Object> payload) {
        StatementSource source = new StatementSource();
        source.setName(trimOrNull(payload.get("name")));
        source.setFolderPath(trimOrNull(payload.get("folderPath")));
        return statementSourceResponse(statementSourceRepository.save(source));
    }

    @PutMapping("/statement-sources/{id}")
    public Map<String, Object> updateStatementSource(@PathVariable Long id, @RequestBody Map<String, Object> payload) {
        StatementSource source = statementSourceRepository.findById(id).orElseThrow();
        source.setName(trimOrNull(payload.get("name")));
        source.setFolderPath(trimOrNull(payload.get("folderPath")));
        // parserRules deliberately left untouched so ingest config survives edits.
        return statementSourceResponse(statementSourceRepository.save(source));
    }

    @DeleteMapping("/statement-sources/{id}")
    public void deleteStatementSource(@PathVariable Long id) {
        statementSourceRepository.deleteById(id);
    }

    // Internal endpoints for managing a source's parser rules. Not surfaced in
    // the main UI (rules are established during ingest setup, not user-edited).

    @GetMapping("/statement-sources/{id}/parser-rules")
    public Map<String, Object> getParserRules(@PathVariable Long id) {
        StatementSource source = statementSourceRepository.findById(id).orElseThrow();
        Map<String, Object> map = new HashMap<>();
        map.put("id", source.getId());
        map.put("parserRules", source.getParserRules() != null ? source.getParserRules() : "");
        return map;
    }

    @PutMapping("/statement-sources/{id}/parser-rules")
    public Map<String, Object> setParserRules(@PathVariable Long id, @RequestBody Map<String, Object> payload) {
        StatementSource source = statementSourceRepository.findById(id).orElseThrow();
        source.setParserRules(payload.get("parserRules") != null ? String.valueOf(payload.get("parserRules")) : null);
        statementSourceRepository.save(source);
        return getParserRules(id);
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Map<String, Object> statementSourceResponse(StatementSource source) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", source.getId());
        map.put("name", source.getName() != null ? source.getName() : "");
        map.put("folderPath", source.getFolderPath() != null ? source.getFolderPath() : "");
        map.put("exclusions", extractExclusions(source.getParserRules()));
        return map;
    }

    /**
     * The user-meaningful part of a source's (otherwise hidden) parser rules:
     * the excludeFromSpend carve-outs, surfaced so they can be seen in the UI.
     */
    private List<Map<String, String>> extractExclusions(String parserRules) {
        List<Map<String, String>> result = new ArrayList<>();
        if (parserRules == null || parserRules.isBlank()) {
            return result;
        }
        try {
            JsonNode arr = objectMapper.readTree(parserRules).get("excludeFromSpend");
            if (arr != null && arr.isArray()) {
                for (JsonNode node : arr) {
                    Map<String, String> exclusion = new HashMap<>();
                    exclusion.put("contains", node.path("contains").asText(""));
                    exclusion.put("reason", node.path("reason").asText(""));
                    result.add(exclusion);
                }
            }
        } catch (Exception ignored) {
            // Malformed rules: just show no exclusions rather than failing the list.
        }
        return result;
    }

    private String trimOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
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

