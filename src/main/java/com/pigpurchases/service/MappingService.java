package com.pigpurchases.service;

import com.pigpurchases.model.AnalysisRun;
import com.pigpurchases.model.AnalysisRunSource;
import com.pigpurchases.model.BudgetEntry;
import com.pigpurchases.model.StatementImport;
import com.pigpurchases.model.StatementSource;
import com.pigpurchases.model.MerchantCategory;
import com.pigpurchases.model.Transaction;
import com.pigpurchases.model.TransactionMapping;
import com.pigpurchases.repository.AnalysisRunRepository;
import com.pigpurchases.repository.AnalysisRunSourceRepository;
import com.pigpurchases.repository.BudgetEntryRepository;
import com.pigpurchases.repository.MerchantCategoryRepository;
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
    @Autowired private MerchantCategoryRepository merchantCategoryRepository;
    @Autowired private AiCategorizationService aiCategorizationService;

    /** One source's contribution to a run, as chosen in the UI. */
    public record SourceSelection(Long sourceId, Long importId) {}

    /**
     * mapped = everything categorized (hint + remembered + live AI + manual);
     * cached = of those, how many came from remembered answers (no API call);
     * aiMapped = of those, how many required a live Claude API call this run.
     */
    public record MapResult(Long runId, String month, int mapped, int parked,
                            int excluded, int aiMapped, int cached) {}

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

        Set<Long> validEntryIds = new HashSet<>();
        for (BudgetEntry entry : entries) {
            validEntryIds.add(entry.getId());
        }

        // Pass 2 — remembered answers. Every parked transaction whose merchant was
        // categorized on a previous run (by AI, or by the user during review) is
        // resolved from the cache, for free. This is what stops re-runs re-paying.
        int cached = applyRememberedCategories(mappings, parkedTransactions, validEntryIds);

        // Pass 3 — AI, on whatever's left. Its answers are written back to the cache
        // so this is the only run that pays for them. Skipped silently with no
        // credentials, leaving those transactions parked.
        int aiMapped = applyAiSuggestions(mappings, parkedTransactions, entries, validEntryIds);

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

        return new MapResult(runId, run.getMonth(), mapped, parked, excluded, aiMapped, cached);
    }

    /**
     * Resolve parked transactions from the merchant cache, removing the ones it
     * handles from {@code parkedTransactions} so the AI pass never sees them. A
     * cached answer pointing at a since-deleted budget entry is dropped (the row
     * is purged), leaving the transaction parked.
     */
    private int applyRememberedCategories(List<TransactionMapping> mappings,
                                          Map<Long, Transaction> parkedTransactions,
                                          Set<Long> validEntryIds) {
        int applied = 0;
        for (TransactionMapping mapping : mappings) {
            if (mapping.getStatus() != TransactionMapping.Status.PARKED) {
                continue;
            }
            Transaction txn = parkedTransactions.get(mapping.getTransactionId());
            if (txn == null) {
                continue;
            }
            String key = HintMatcher.normalize(txn.getDescription());
            Optional<MerchantCategory> remembered = merchantCategoryRepository.findByMerchantKey(key);
            if (remembered.isEmpty()) {
                continue;
            }
            MerchantCategory mc = remembered.get();
            if (!validEntryIds.contains(mc.getBudgetEntryId())) {
                merchantCategoryRepository.delete(mc); // entry gone; forget the stale answer
                continue;
            }
            boolean manual = mc.getSource() == MerchantCategory.Source.MANUAL;
            mapping.setBudgetEntryId(mc.getBudgetEntryId());
            mapping.setStatus(manual ? TransactionMapping.Status.MAPPED_MANUAL
                                     : TransactionMapping.Status.MAPPED_AI);
            mapping.setReason("Remembered — " + (manual ? "your earlier categorization"
                    : "previously categorized by AI"));
            parkedTransactions.remove(mapping.getTransactionId());
            applied++;
        }
        return applied;
    }

    /**
     * Ask the AI to categorize the parked transactions, and promote the ones it
     * answers confidently to MAPPED_AI. Repeated merchants are collapsed to one
     * candidate, so five Fresh Market visits cost a single line in the request and all
     * five resolve identically.
     */
    private int applyAiSuggestions(List<TransactionMapping> mappings,
                                   Map<Long, Transaction> parkedTransactions,
                                   List<BudgetEntry> entries,
                                   Set<Long> validEntryIds) {
        if (parkedTransactions.isEmpty() || !aiCategorizationService.isAvailable()) {
            return 0;
        }

        Map<Long, String> keyByTransaction = new LinkedHashMap<>();
        Map<String, Transaction> sampleByKey = new LinkedHashMap<>();
        List<AiCategorizationService.Candidate> candidates = new ArrayList<>();
        for (Transaction txn : parkedTransactions.values()) {
            String key = HintMatcher.normalize(txn.getDescription());
            keyByTransaction.put(txn.getId(), key);
            sampleByKey.putIfAbsent(key, txn);
            candidates.add(new AiCategorizationService.Candidate(
                    key, txn.getDescription(), txn.getVendor()));
        }

        Map<String, AiCategorizationService.Suggestion> suggestions = aiCategorizationService
                .categorize(AiCategorizationService.dedupe(candidates), entries);
        if (suggestions.isEmpty()) {
            return 0;
        }

        // Remember each confident answer so no later run pays for this merchant again.
        for (Map.Entry<String, AiCategorizationService.Suggestion> e : suggestions.entrySet()) {
            AiCategorizationService.Suggestion s = e.getValue();
            if (s.budgetEntryId() == null || !validEntryIds.contains(s.budgetEntryId())) {
                continue;
            }
            Transaction sample = sampleByKey.get(e.getKey());
            remember(e.getKey(), s.budgetEntryId(), MerchantCategory.Source.AI,
                    sample != null ? sample.getDescription() : null, s.reason());
        }

        int promoted = 0;
        for (TransactionMapping mapping : mappings) {
            if (mapping.getStatus() != TransactionMapping.Status.PARKED) {
                continue;
            }
            AiCategorizationService.Suggestion suggestion =
                    suggestions.get(keyByTransaction.get(mapping.getTransactionId()));
            if (suggestion == null || suggestion.budgetEntryId() == null
                    || !validEntryIds.contains(suggestion.budgetEntryId())) {
                continue;
            }
            mapping.setBudgetEntryId(suggestion.budgetEntryId());
            mapping.setStatus(TransactionMapping.Status.MAPPED_AI);
            mapping.setReason(suggestion.reason());
            promoted++;
        }
        return promoted;
    }

    /**
     * Upsert a remembered categorization. A MANUAL answer always wins; an AI
     * answer never overwrites an existing row (the merchant is only sent to the
     * AI when it wasn't already cached, so in practice this only guards races).
     */
    private void remember(String merchantKey, Long budgetEntryId, MerchantCategory.Source source,
                          String sampleDescription, String reason) {
        MerchantCategory existing = merchantCategoryRepository.findByMerchantKey(merchantKey).orElse(null);
        if (existing != null && source == MerchantCategory.Source.AI
                && existing.getSource() == MerchantCategory.Source.MANUAL) {
            return; // never let AI clobber a human correction
        }
        MerchantCategory mc = existing != null ? existing
                : new MerchantCategory(merchantKey, null, source, sampleDescription, reason, null);
        mc.setBudgetEntryId(budgetEntryId);
        mc.setSource(source);
        if (sampleDescription != null) {
            mc.setSampleDescription(sampleDescription);
        }
        mc.setReason(reason);
        mc.setUpdatedAt(LocalDateTime.now());
        merchantCategoryRepository.save(mc);
    }

    /** Categorize one transaction by hand, e.g. while working through the parked bucket. */
    @Transactional
    public TransactionMapping assign(Long runId, Long transactionId, Long budgetEntryId) {
        TransactionMapping mapping = mappingRepository.findByAnalysisRunIdAndTransactionId(runId, transactionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Transaction " + transactionId + " is not part of run " + runId));

        // A manual decision is also taught to the merchant cache, so the same
        // merchant maps itself on every future run — the "user feedback loop".
        String merchantKey = transactionRepository.findById(transactionId)
                .map(txn -> HintMatcher.normalize(txn.getDescription())).orElse(null);

        if (budgetEntryId == null) {
            mapping.setBudgetEntryId(null);
            mapping.setStatus(TransactionMapping.Status.PARKED);
            mapping.setReason("Un-categorized by hand");
            // Deliberately parking it means "this was wrong" — forget the remembered answer.
            if (merchantKey != null) {
                merchantCategoryRepository.findByMerchantKey(merchantKey)
                        .ifPresent(merchantCategoryRepository::delete);
            }
        } else {
            BudgetEntry entry = budgetEntryRepository.findById(budgetEntryId)
                    .orElseThrow(() -> new IllegalArgumentException("No such budget entry: " + budgetEntryId));
            mapping.setBudgetEntryId(entry.getId());
            mapping.setStatus(TransactionMapping.Status.MAPPED_MANUAL);
            mapping.setReason("Categorized by hand");
            if (merchantKey != null) {
                Transaction txn = transactionRepository.findById(transactionId).orElse(null);
                remember(merchantKey, entry.getId(), MerchantCategory.Source.MANUAL,
                        txn != null ? txn.getDescription() : null, "Categorized by hand");
            }
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
