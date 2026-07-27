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
            if (com.pigpurchases.service.ManualEntryService.MANUAL_MONTH.equals(run.getMonth())) {
                continue; // the hidden run that owns manual entries isn't a real mapping run
            }
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

    // ---- Per-file mapping (statement-based, month-free) ---------------------

    /** Every ingested statement with its mapping status, for the Mapping screen. */
    @GetMapping("/mapping/files")
    public Map<String, Object> mappingFiles() {
        List<Map<String, Object>> files = new ArrayList<>();
        for (StatementSource source : sourceRepository.findAll()) {
            for (StatementImport imp : importRepository.findByStatementSourceIdOrderByStatementDateDesc(source.getId())) {
                Map<String, Object> f = new HashMap<>();
                f.put("importId", imp.getId());
                f.put("sourceId", source.getId());
                f.put("sourceName", source.getName());
                f.put("fileName", imp.getFileName());
                f.put("statementDate", imp.getStatementDate() != null ? imp.getStatementDate().toString() : null);
                f.put("transactionCount", imp.getTransactionCount());
                Optional<AnalysisRun> run = mappingService.consumingRun(imp.getId());
                if (run.isPresent()) {
                    AnalysisRun r = run.get();
                    f.put("mapped", true);
                    f.put("runId", r.getId());
                    f.put("mappedCount", r.getMappedCount());
                    f.put("parkedCount", r.getParkedCount());
                    f.put("excludedCount", r.getExcludedCount());
                } else {
                    f.put("mapped", false);
                }
                files.add(f);
            }
        }
        Map<String, Object> resp = new HashMap<>();
        resp.put("files", files);
        resp.put("unmappedCount", mappingService.unmappedImports().size());
        resp.put("aiAvailable", aiCategorizationService.isAvailable());
        return resp;
    }

    /** Map every statement that hasn't been mapped yet. */
    @PostMapping("/mapping/map-unmapped")
    public Map<String, Object> mapUnmapped() {
        return batchResponse(mappingService.mapUnmapped());
    }

    /** Re-map the chosen statements. */
    @PostMapping("/mapping/remap")
    public Map<String, Object> remap(@RequestBody Map<String, Object> body) {
        List<Long> ids = new ArrayList<>();
        Object raw = body.get("importIds");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Number n) ids.add(n.longValue());
            }
        }
        return batchResponse(mappingService.remapImports(ids));
    }

    /** One-time: split any legacy month-run into per-file runs. Idempotent. */
    @PostMapping("/mapping/migrate")
    public Map<String, Object> migrate() {
        return Map.of("split", mappingService.migrateToPerFile());
    }

    private Map<String, Object> batchResponse(MappingService.MapBatchResult r) {
        Map<String, Object> m = new HashMap<>();
        m.put("files", r.files());
        m.put("mapped", r.mapped());
        m.put("parked", r.parked());
        m.put("excluded", r.excluded());
        m.put("aiMapped", r.aiMapped());
        m.put("cached", r.cached());
        return m;
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

    /**
     * Update one transaction's mapping by hand. {@code exclude:true} marks it as
     * not-spend (and remembers that); otherwise {@code budgetEntryId} assigns it
     * to an entry, or null parks it again as "Other".
     */
    @PutMapping("/analysis-runs/{id}/mappings/{transactionId}")
    public Map<String, Object> assign(@PathVariable Long id, @PathVariable Long transactionId,
                                      @RequestBody Map<String, Object> payload) {
        TransactionMapping mapping = Boolean.TRUE.equals(payload.get("exclude"))
                ? mappingService.exclude(id, transactionId)
                : mappingService.assign(id, transactionId, asLong(payload.get("budgetEntryId")));
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
