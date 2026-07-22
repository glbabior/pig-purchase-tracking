package com.pigpurchases.server;

import com.pigpurchases.model.AnalysisRun;
import com.pigpurchases.model.AnalysisRunSource;
import com.pigpurchases.model.BudgetEntry;
import com.pigpurchases.model.StatementImport;
import com.pigpurchases.model.StatementSource;
import com.pigpurchases.model.Transaction;
import com.pigpurchases.model.TransactionMapping;
import com.pigpurchases.repository.AnalysisRunRepository;
import com.pigpurchases.repository.AnalysisRunSourceRepository;
import com.pigpurchases.repository.BudgetEntryRepository;
import com.pigpurchases.repository.StatementImportRepository;
import com.pigpurchases.repository.StatementSourceRepository;
import com.pigpurchases.repository.TransactionMappingRepository;
import com.pigpurchases.repository.TransactionRepository;
import com.pigpurchases.service.MappingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Monthly mapping runs: setting one up, executing the mapping, reviewing what
 * was parked, and correcting it by hand.
 */
@RestController
@RequestMapping("/api")
public class MappingController {

    @Autowired private MappingService mappingService;
    @Autowired private com.pigpurchases.service.AiCategorizationService aiCategorizationService;
    @Autowired private AnalysisRunRepository runRepository;
    @Autowired private AnalysisRunSourceRepository runSourceRepository;
    @Autowired private TransactionMappingRepository mappingRepository;
    @Autowired private StatementSourceRepository sourceRepository;
    @Autowired private StatementImportRepository importRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private BudgetEntryRepository budgetEntryRepository;

    /**
     * Everything the "Map Transactions" dialog needs: each source with the
     * statements it can contribute. Consumed statements are omitted unless
     * explicitly requested, in which case they carry the run that took them.
     */
    @GetMapping("/mapping/setup")
    public Map<String, Object> setup(@RequestParam(required = false, defaultValue = "false") boolean includeConsumed) {
        Set<Long> consumed = mappingService.consumedImportIds();

        List<Map<String, Object>> sources = new ArrayList<>();
        for (StatementSource source : sourceRepository.findAll()) {
            List<Map<String, Object>> statements = new ArrayList<>();
            for (StatementImport imp : importRepository.findByStatementSourceIdOrderByStatementDateDesc(source.getId())) {
                boolean isConsumed = consumed.contains(imp.getId());
                if (isConsumed && !includeConsumed) {
                    continue;
                }
                Map<String, Object> statement = new HashMap<>();
                statement.put("importId", imp.getId());
                statement.put("statementDate", imp.getStatementDate() != null ? imp.getStatementDate().toString() : null);
                statement.put("fileName", imp.getFileName());
                statement.put("transactionCount", imp.getTransactionCount());
                statement.put("consumed", isConsumed);
                statement.put("consumedByMonth", isConsumed
                        ? mappingService.consumingRun(imp.getId()).map(AnalysisRun::getMonth).orElse(null) : null);
                statements.add(statement);
            }
            Map<String, Object> map = new HashMap<>();
            map.put("sourceId", source.getId());
            map.put("name", source.getName());
            map.put("statements", statements);
            sources.add(map);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("sources", sources);
        // Every source must contribute, so the dialog can say up front when it can't proceed.
        response.put("ready", sources.stream().noneMatch(s -> ((List<?>) s.get("statements")).isEmpty()));
        // So the dialog can say whether unmatched transactions will be sent for
        // categorization or will simply land in "Other".
        response.put("aiAvailable", aiCategorizationService.isAvailable());
        return response;
    }

    @GetMapping("/analysis-runs")
    public List<Map<String, Object>> listRuns() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AnalysisRun run : runRepository.findAllByOrderByMonthDesc()) {
            result.add(runResponse(run));
        }
        return result;
    }

    @PostMapping("/analysis-runs")
    @SuppressWarnings("unchecked")
    public Map<String, Object> createRun(@RequestBody Map<String, Object> payload) {
        String month = payload.get("month") != null ? String.valueOf(payload.get("month")).trim() : null;
        boolean allowConsumed = Boolean.TRUE.equals(payload.get("allowConsumed"));

        List<MappingService.SourceSelection> selections = new ArrayList<>();
        Object raw = payload.get("selections");
        if (!(raw instanceof List)) {
            throw new IllegalArgumentException("selections must be a list of {sourceId, importId}");
        }
        for (Object item : (List<Object>) raw) {
            Map<String, Object> selection = (Map<String, Object>) item;
            selections.add(new MappingService.SourceSelection(
                    asLong(selection.get("sourceId")), asLong(selection.get("importId"))));
        }
        return runResponse(mappingService.createRun(month, selections, allowConsumed));
    }

    /** Create and immediately map, which is what the dialog's Save does. */
    @PostMapping("/analysis-runs/{id}/map")
    public Map<String, Object> mapRun(@PathVariable Long id) {
        MappingService.MapResult result = mappingService.map(id);
        Map<String, Object> response = runResponse(runRepository.findById(id).orElseThrow());
        response.put("justMapped", Map.of(
                "mapped", result.mapped(), "parked", result.parked(),
                "excluded", result.excluded(), "aiMapped", result.aiMapped(),
                "cached", result.cached()));
        return response;
    }

    @DeleteMapping("/analysis-runs/{id}")
    public void deleteRun(@PathVariable Long id) {
        mappingService.deleteRun(id);
    }

    /**
     * The run's transactions, newest first. {@code status=PARKED} gives the
     * "Other" bucket that review sessions work through.
     */
    @GetMapping("/analysis-runs/{id}/mappings")
    public List<Map<String, Object>> mappings(@PathVariable Long id,
                                              @RequestParam(required = false) String status) {
        List<TransactionMapping> rows = status == null || status.isBlank()
                ? mappingRepository.findByAnalysisRunId(id)
                : mappingRepository.findByAnalysisRunIdAndStatus(id, TransactionMapping.Status.valueOf(status));

        Map<Long, String> entryNames = new LinkedHashMap<>();
        for (BudgetEntry entry : budgetEntryRepository.findAll()) {
            entryNames.put(entry.getId(), entry.getName());
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (TransactionMapping m : rows) {
            Optional<Transaction> txn = transactionRepository.findById(m.getTransactionId());
            Map<String, Object> map = new HashMap<>();
            map.put("transactionId", m.getTransactionId());
            map.put("status", m.getStatus().name());
            map.put("reason", m.getReason());
            map.put("budgetEntryId", m.getBudgetEntryId());
            map.put("budgetEntryName", m.getBudgetEntryId() != null
                    ? entryNames.get(m.getBudgetEntryId()) : (m.countsAsSpend() ? "Other" : null));
            txn.ifPresent(t -> {
                map.put("date", t.getTransactionDate() != null ? t.getTransactionDate().toString() : null);
                map.put("description", t.getDescription());
                map.put("vendor", t.getVendor());
                map.put("amount", t.getAmount() != null ? t.getAmount().toPlainString() : null);
                map.put("type", t.getType());
                map.put("sourceId", t.getStatementSourceId());
            });
            result.add(map);
        }
        result.sort((a, b) -> String.valueOf(b.get("date")).compareTo(String.valueOf(a.get("date"))));
        return result;
    }

    /** Categorize one transaction by hand; a null budgetEntryId parks it again. */
    @PutMapping("/analysis-runs/{id}/mappings/{transactionId}")
    public Map<String, Object> assign(@PathVariable Long id, @PathVariable Long transactionId,
                                      @RequestBody Map<String, Object> payload) {
        TransactionMapping mapping = mappingService.assign(id, transactionId, asLong(payload.get("budgetEntryId")));
        Map<String, Object> response = new HashMap<>();
        response.put("transactionId", mapping.getTransactionId());
        response.put("status", mapping.getStatus().name());
        response.put("budgetEntryId", mapping.getBudgetEntryId());
        response.put("run", runResponse(runRepository.findById(id).orElseThrow()));
        return response;
    }

    private Map<String, Object> runResponse(AnalysisRun run) {
        List<Map<String, Object>> sources = new ArrayList<>();
        for (AnalysisRunSource link : runSourceRepository.findByAnalysisRunId(run.getId())) {
            Map<String, Object> map = new HashMap<>();
            sourceRepository.findById(link.getStatementSourceId())
                    .ifPresent(s -> map.put("sourceName", s.getName()));
            importRepository.findById(link.getStatementImportId()).ifPresent(imp -> {
                map.put("importId", imp.getId());
                map.put("fileName", imp.getFileName());
                map.put("statementDate", imp.getStatementDate() != null ? imp.getStatementDate().toString() : null);
            });
            map.put("sourceId", link.getStatementSourceId());
            sources.add(map);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("id", run.getId());
        response.put("month", run.getMonth());
        response.put("status", run.getStatus().name());
        response.put("createdAt", run.getCreatedAt() != null ? run.getCreatedAt().toString() : null);
        response.put("mappedAt", run.getMappedAt() != null ? run.getMappedAt().toString() : null);
        response.put("mappedCount", run.getMappedCount());
        response.put("parkedCount", run.getParkedCount());
        response.put("excludedCount", run.getExcludedCount());
        response.put("sources", sources);
        return response;
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() || text.equals("null") ? null : Long.valueOf(text);
    }

    /** Invalid setup (missing source, consumed statement, bad month) -> 400 rather than 500. */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleBadRequest(IllegalArgumentException ex) {
        return Map.of("error", ex.getMessage() != null ? ex.getMessage() : "Bad request");
    }

    /** Mapping already running for this run -> 409 rather than a lock-timeout 500. */
    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handleConflict(IllegalStateException ex) {
        return Map.of("error", ex.getMessage() != null ? ex.getMessage() : "Already in progress");
    }
}
