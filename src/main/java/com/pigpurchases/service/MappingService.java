package com.pigpurchases.service;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds and executes monthly mapping runs.
 *
 * A run binds a user-asserted month to exactly one ingested statement per
 * statement source, then resolves every transaction in those statements to a
 * budget entry. See the README's "Transaction Mapping — Analysis Runs" section
 * for the methodology this implements.
 */
@Service
public class MappingService {

    private static final Pattern MONTH = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");

    @Autowired private AnalysisRunRepository runRepository;
    @Autowired private AnalysisRunSourceRepository runSourceRepository;
    @Autowired private TransactionMappingRepository mappingRepository;
    @Autowired private StatementSourceRepository sourceRepository;
    @Autowired private StatementImportRepository importRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private BudgetEntryRepository budgetEntryRepository;
    @Autowired private AiCategorizationService aiCategorizationService;

    /** One source's contribution to a run, as chosen in the UI. */
    public record SourceSelection(Long sourceId, Long importId) {}

    public record MapResult(Long runId, String month, int mapped, int parked, int excluded, int aiMapped) {}

    // ---- Run setup ---------------------------------------------------------

    /**
     * Create a run for {@code month}. Every statement source must be represented
     * exactly once, and each chosen statement must be unconsumed unless
     * {@code allowConsumed} says the user explicitly asked for it.
     */
    @Transactional
    public AnalysisRun createRun(String month, List<SourceSelection> selections, boolean allowConsumed) {
        if (month == null || !MONTH.matcher(month).matches()) {
            throw new IllegalArgumentException("Month must be in YYYY-MM format, got: " + month);
        }
        if (runRepository.findByMonth(month).isPresent()) {
            throw new IllegalArgumentException("A run already exists for " + month);
        }

        List<StatementSource> allSources = sourceRepository.findAll();
        if (allSources.isEmpty()) {
            throw new IllegalArgumentException("No statement sources are configured");
        }

        // Exactly one selection per source, covering every source.
        Map<Long, Long> chosen = new LinkedHashMap<>();
        for (SourceSelection selection : selections) {
            if (selection.sourceId() == null || selection.importId() == null) {
                throw new IllegalArgumentException("Each selection needs a sourceId and an importId");
            }
            if (chosen.putIfAbsent(selection.sourceId(), selection.importId()) != null) {
                throw new IllegalArgumentException("More than one statement chosen for source " + selection.sourceId());
            }
        }
        List<String> missing = new ArrayList<>();
        for (StatementSource source : allSources) {
            if (!chosen.containsKey(source.getId())) {
                missing.add(source.getName());
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Every source needs a statement; missing: " + String.join(", ", missing));
        }

        // Each import must exist, belong to its claimed source, and be free.
        for (Map.Entry<Long, Long> entry : chosen.entrySet()) {
            StatementImport imp = importRepository.findById(entry.getValue())
                    .orElseThrow(() -> new IllegalArgumentException("No such statement import: " + entry.getValue()));
            if (!imp.getStatementSourceId().equals(entry.getKey())) {
                throw new IllegalArgumentException(
                        "Statement " + imp.getFileName() + " does not belong to the source it was chosen for");
            }
            if (!allowConsumed && !runSourceRepository.findByStatementImportId(imp.getId()).isEmpty()) {
                throw new IllegalArgumentException(
                        "Statement " + imp.getFileName() + " has already been used by another run");
            }
        }

        AnalysisRun run = runRepository.save(new AnalysisRun(month, LocalDateTime.now()));
        for (Map.Entry<Long, Long> entry : chosen.entrySet()) {
            runSourceRepository.save(new AnalysisRunSource(run.getId(), entry.getKey(), entry.getValue()));
        }
        return run;
    }

    /** Deleting a run releases its statements back into the pool. */
    @Transactional
    public void deleteRun(Long runId) {
        mappingRepository.deleteByAnalysisRunId(runId);
        runSourceRepository.deleteByAnalysisRunId(runId);
        runRepository.deleteById(runId);
    }

    /** Statement import ids already consumed by any run. */
    public Set<Long> consumedImportIds() {
        Set<Long> consumed = new HashSet<>();
        for (AnalysisRunSource link : runSourceRepository.findAll()) {
            consumed.add(link.getStatementImportId());
        }
        return consumed;
    }

    /** Which run consumed a given import, if any. */
    public Optional<AnalysisRun> consumingRun(Long importId) {
        List<AnalysisRunSource> links = runSourceRepository.findByStatementImportId(importId);
        return links.isEmpty() ? Optional.empty() : runRepository.findById(links.get(0).getAnalysisRunId());
    }

    // ---- Mapping -----------------------------------------------------------

    /**
     * Resolve every transaction in the run's statements. Re-running replaces the
     * previous results for this run only; the ingested transactions themselves
     * are never touched.
     */
    @Transactional
    public MapResult map(Long runId) {
        AnalysisRun run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("No such run: " + runId));

        // Flush the removals before inserting the replacements: Hibernate orders
        // inserts ahead of deletes within a flush, which would otherwise trip the
        // (run, transaction) unique constraint when re-running a run.
        mappingRepository.deleteByAnalysisRunId(runId);
        mappingRepository.flush();

        List<BudgetEntry> entries = budgetEntryRepository.findAll();
        HintMatcher matcher = new HintMatcher(entries);

        // Pass 1 — deterministic. Build every mapping in memory first so the AI pass
        // can see the whole parked set at once and de-duplicate repeated merchants.
        List<TransactionMapping> mappings = new ArrayList<>();
        Map<Long, Transaction> parkedTransactions = new LinkedHashMap<>();

        for (AnalysisRunSource link : runSourceRepository.findByAnalysisRunId(runId)) {
            for (Transaction txn : transactionRepository.findByStatementImportId(link.getStatementImportId())) {
                if (txn.isExcludeFromSpend()) {
                    // A transfer the source's parser rules already carved out. Recorded
                    // for completeness so the run accounts for every line, never counted.
                    mappings.add(new TransactionMapping(runId, txn.getId(), null,
                            TransactionMapping.Status.EXCLUDED, "Excluded by source parser rules"));
                    continue;
                }
                Optional<HintMatcher.Match> match = matcher.match(txn.getDescription(), txn.getVendor());
                if (match.isPresent()) {
                    mappings.add(new TransactionMapping(runId, txn.getId(), match.get().entry().getId(),
                            TransactionMapping.Status.MAPPED_HINT,
                            "Matched on \"" + match.get().matchedOn() + "\""));
                } else {
                    // Parked for now: shows as "Other" and still counts as spend.
                    TransactionMapping parkedMapping = new TransactionMapping(runId, txn.getId(), null,
                            TransactionMapping.Status.PARKED, "No hint or name matched");
                    mappings.add(parkedMapping);
                    parkedTransactions.put(txn.getId(), txn);
                }
            }
        }

        // Pass 2 — AI, on whatever the matcher couldn't resolve. Skipped silently when
        // no credentials are configured, leaving those transactions parked.
        int aiMapped = applyAiSuggestions(mappings, parkedTransactions, entries);

        int mapped = 0;
        int parked = 0;
        int excluded = 0;
        for (TransactionMapping mapping : mappings) {
            switch (mapping.getStatus()) {
                case PARKED -> parked++;
                case EXCLUDED -> excluded++;
                default -> mapped++;
            }
            mappingRepository.save(mapping);
        }

        run.setMappedCount(mapped);
        run.setParkedCount(parked);
        run.setExcludedCount(excluded);
        run.setStatus(AnalysisRun.Status.MAPPED);
        run.setMappedAt(LocalDateTime.now());
        runRepository.save(run);

        return new MapResult(runId, run.getMonth(), mapped, parked, excluded, aiMapped);
    }

    /**
     * Ask the AI to categorize the parked transactions, and promote the ones it
     * answers confidently to MAPPED_AI. Repeated merchants are collapsed to one
     * candidate, so five Fresh Market visits cost a single line in the request and all
     * five resolve identically.
     */
    private int applyAiSuggestions(List<TransactionMapping> mappings,
                                   Map<Long, Transaction> parkedTransactions,
                                   List<BudgetEntry> entries) {
        if (parkedTransactions.isEmpty() || !aiCategorizationService.isAvailable()) {
            return 0;
        }

        Map<Long, String> keyByTransaction = new LinkedHashMap<>();
        List<AiCategorizationService.Candidate> candidates = new ArrayList<>();
        for (Transaction txn : parkedTransactions.values()) {
            String key = HintMatcher.normalize(txn.getDescription());
            keyByTransaction.put(txn.getId(), key);
            candidates.add(new AiCategorizationService.Candidate(
                    key, txn.getDescription(), txn.getVendor()));
        }

        Map<String, AiCategorizationService.Suggestion> suggestions = aiCategorizationService
                .categorize(AiCategorizationService.dedupe(candidates), entries);
        if (suggestions.isEmpty()) {
            return 0;
        }

        int promoted = 0;
        for (TransactionMapping mapping : mappings) {
            if (mapping.getStatus() != TransactionMapping.Status.PARKED) {
                continue;
            }
            AiCategorizationService.Suggestion suggestion =
                    suggestions.get(keyByTransaction.get(mapping.getTransactionId()));
            if (suggestion == null) {
                continue;
            }
            mapping.setBudgetEntryId(suggestion.budgetEntryId());
            mapping.setStatus(TransactionMapping.Status.MAPPED_AI);
            mapping.setReason(suggestion.reason());
            promoted++;
        }
        return promoted;
    }

    /** Categorize one transaction by hand, e.g. while working through the parked bucket. */
    @Transactional
    public TransactionMapping assign(Long runId, Long transactionId, Long budgetEntryId) {
        TransactionMapping mapping = mappingRepository.findByAnalysisRunIdAndTransactionId(runId, transactionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Transaction " + transactionId + " is not part of run " + runId));

        if (budgetEntryId == null) {
            mapping.setBudgetEntryId(null);
            mapping.setStatus(TransactionMapping.Status.PARKED);
            mapping.setReason("Un-categorized by hand");
        } else {
            BudgetEntry entry = budgetEntryRepository.findById(budgetEntryId)
                    .orElseThrow(() -> new IllegalArgumentException("No such budget entry: " + budgetEntryId));
            mapping.setBudgetEntryId(entry.getId());
            mapping.setStatus(TransactionMapping.Status.MAPPED_MANUAL);
            mapping.setReason("Categorized by hand");
        }
        mappingRepository.save(mapping);
        recount(runId);
        return mapping;
    }

    /** Keep the run's counts in step after a manual change. */
    private void recount(Long runId) {
        int mapped = 0;
        int parked = 0;
        int excluded = 0;
        for (TransactionMapping m : mappingRepository.findByAnalysisRunId(runId)) {
            switch (m.getStatus()) {
                case PARKED -> parked++;
                case EXCLUDED -> excluded++;
                default -> mapped++;
            }
        }
        AnalysisRun run = runRepository.findById(runId).orElseThrow();
        run.setMappedCount(mapped);
        run.setParkedCount(parked);
        run.setExcludedCount(excluded);
        runRepository.save(run);
    }
}
