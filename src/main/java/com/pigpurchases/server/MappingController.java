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
