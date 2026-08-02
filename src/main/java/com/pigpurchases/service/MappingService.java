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
import java.util.concurrent.atomic.AtomicBoolean;
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

    /**
     * Set while any mapping is running. Mapping makes real (slow) API calls and
     * rewrites shared state — the run's mappings, and the merchant cache — so a
     * second request (an impatient double-click, or a second browser tab) would
     * otherwise collide on row locks and unique constraints deep inside the run and
     * surface as a lock-timeout stack trace. This makes the second caller fail fast
     * and clearly instead, which {@code MappingController} reports as a 409.
     *
     * <p>Deliberately one flag rather than a set keyed by run: two concurrent
     * "map every unmapped statement" requests each create their <b>own</b> run for
     * the same statement, so per-run keys would never collide — and both would then
     * insert an {@code analysis_run_sources} row for the same uniquely-indexed
     * import, which fails at commit. The thing worth serializing is mapping itself,
     * not a particular run.
     *
     * <p>Known limit: the flag is cleared as the mapping call returns, fractionally
     * before the caller's transaction commits, so a double-submit landing inside
     * that window can still race. That is a window of milliseconds rather than the
     * length of a whole AI-calling batch; closing it completely means moving the
     * guard outside the transaction boundary, which isn't worth the restructuring
     * for a single-user desktop app.
     */
    private final AtomicBoolean mappingInProgress = new AtomicBoolean();

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

    // ---- Per-file mapping (statement-based, month-free) ---------------------

    /** Result of mapping a batch of files. */
    public record MapBatchResult(int files, int mapped, int parked, int excluded, int aiMapped, int cached) {}

    /** The internal, month-free run label for a single statement's mapping. */
    private static String fileRunMonth(Long importId) {
        return "import-" + importId;
    }

    /** Statement imports that haven't been mapped yet. */
    @Transactional(readOnly = true)
    public List<StatementImport> unmappedImports() {
        Set<Long> consumed = consumedImportIds();
        List<StatementImport> out = new ArrayList<>();
        for (StatementImport imp : importRepository.findAll()) {
            if (!consumed.contains(imp.getId())) {
                out.add(imp);
            }
        }
        return out;
    }

    /** Create a mapping run bound to a single statement import. */
    @Transactional
    public AnalysisRun createFileRun(Long importId) {
        StatementImport imp = importRepository.findById(importId)
                .orElseThrow(() -> new IllegalArgumentException("No such statement import: " + importId));
        if (!runSourceRepository.findByStatementImportId(importId).isEmpty()) {
            throw new IllegalArgumentException("Statement " + imp.getFileName() + " is already mapped");
        }
        AnalysisRun run = runRepository.save(new AnalysisRun(fileRunMonth(importId), LocalDateTime.now()));
        runSourceRepository.save(new AnalysisRunSource(run.getId(), imp.getStatementSourceId(), importId));
        return run;
    }

    /** Map every statement that hasn't been mapped yet — one run per file. */
    @Transactional
    public MapBatchResult mapUnmapped() {
        return mapFiles(unmappedImports().stream().map(StatementImport::getId).toList());
    }

    /** Re-map the given statements (creating a run for any that aren't mapped yet). */
    @Transactional
    public MapBatchResult remapImports(List<Long> importIds) {
        return mapFiles(importIds);
    }

    // Runs within the caller's transaction (mapUnmapped/remapImports are @Transactional);
    // calls doMap directly rather than the proxied map() so the transaction actually
    // applies — which is why the guard is taken here explicitly. This, not map(), is
    // the path both mapping buttons reach.
    private MapBatchResult mapFiles(List<Long> importIds) {
        beginMapping();
        try {
            int files = 0, mapped = 0, parked = 0, excluded = 0, ai = 0, cached = 0;
            for (Long importId : importIds) {
                AnalysisRun run = consumingRun(importId).orElseGet(() -> createFileRun(importId));
                MapResult r = doMap(run.getId());
                files++;
                mapped += r.mapped();
                parked += r.parked();
                excluded += r.excluded();
                ai += r.aiMapped();
                cached += r.cached();
            }
            return new MapBatchResult(files, mapped, parked, excluded, ai, cached);
        } finally {
            endMapping();
        }
    }

    /**
     * One-time migration: split any legacy month-run (created before mapping went
     * per-file) into one run per statement file, re-pointing each existing mapping
     * to its file's run. Idempotent — runs already per-file ("import-…") or the
     * hidden "manual" run are left untouched, so re-invoking does nothing.
     */
    @Transactional
    public int migrateToPerFile() {
        int split = 0;
        for (AnalysisRun run : runRepository.findAll()) {
            String m = run.getMonth();
            if (m == null || "manual".equals(m) || m.startsWith("import-")) {
                continue;
            }
            List<AnalysisRunSource> sources = runSourceRepository.findByAnalysisRunId(run.getId());
            List<TransactionMapping> runMappings = mappingRepository.findByAnalysisRunId(run.getId());
            for (AnalysisRunSource src : sources) {
                Long importId = src.getStatementImportId();
                Set<Long> importTxnIds = new HashSet<>();
                for (Transaction t : transactionRepository.findByStatementImportId(importId)) {
                    importTxnIds.add(t.getId());
                }
                AnalysisRun fileRun = new AnalysisRun(fileRunMonth(importId),
                        run.getCreatedAt() != null ? run.getCreatedAt() : LocalDateTime.now());
                fileRun.setStatus(AnalysisRun.Status.MAPPED);
                fileRun.setMappedAt(run.getMappedAt());
                fileRun = runRepository.save(fileRun);
                // Reassign the EXISTING source link (statement_import_id is uniquely
                // indexed, so a second row for the same import isn't allowed).
                src.setAnalysisRunId(fileRun.getId());
                runSourceRepository.save(src);

                int mc = 0, pc = 0, ec = 0;
                for (TransactionMapping tm : runMappings) {
                    if (importTxnIds.contains(tm.getTransactionId())) {
                        tm.setAnalysisRunId(fileRun.getId());
                        mappingRepository.save(tm);
                        switch (tm.getStatus()) {
                            case PARKED -> pc++;
                            case EXCLUDED, EXCLUDED_ONCE -> ec++;
                            default -> mc++;
                        }
                    }
                }
                fileRun.setMappedCount(mc);
                fileRun.setParkedCount(pc);
                fileRun.setExcludedCount(ec);
                runRepository.save(fileRun);
            }
            // Source links were reassigned to the per-file runs; any leftover
            // mappings (none expected) are cleaned before dropping the old run.
            mappingRepository.deleteByAnalysisRunId(run.getId());
            runRepository.deleteById(run.getId());
            split++;
        }
        return split;
    }

    // ---- Mapping -----------------------------------------------------------

    /**
     * Resolve every transaction in the run's statements. Re-running replaces the
     * previous results for this run only; the ingested transactions themselves
     * are never touched.
     */
    @Transactional
    public MapResult map(Long runId) {
        beginMapping();
        try {
            return doMap(runId);
        } finally {
            endMapping();
        }
    }

    /** Claim the mapping guard, or fail fast if a mapping is already under way. */
    private void beginMapping() {
        if (!mappingInProgress.compareAndSet(false, true)) {
            throw new IllegalStateException(
                    "Mapping is already running — please wait for it to finish.");
        }
    }

    private void endMapping() {
        mappingInProgress.set(false);
    }

    private MapResult doMap(Long runId) {
        AnalysisRun run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("No such run: " + runId));

        // One-off exclusions are per-transaction decisions with no rule behind them,
        // so unlike every other manual choice they can't be rebuilt from the merchant
        // cache. Carry them over the rebuild explicitly, or re-running a statement
        // would silently turn a deliberately-excluded charge back into spend.
        Map<Long, String> excludedOnce = new LinkedHashMap<>();
        for (TransactionMapping prior : mappingRepository.findByAnalysisRunId(runId)) {
            if (prior.getStatus() == TransactionMapping.Status.EXCLUDED_ONCE) {
                excludedOnce.put(prior.getTransactionId(), prior.getReason());
            }
        }

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
        // Hint-matched rows are kept too, so pass 2 can let an explicit manual decision
        // override the pattern. Deliberately separate from parkedTransactions, which the
        // AI pass consumes — these already have an answer and must never be sent.
        Map<Long, Transaction> hintMatched = new LinkedHashMap<>();

        for (AnalysisRunSource link : runSourceRepository.findByAnalysisRunId(runId)) {
            for (Transaction txn : transactionRepository.findByStatementImportId(link.getStatementImportId())) {
                if (excludedOnce.containsKey(txn.getId())) {
                    // The user's one-off call on this exact transaction outranks every
                    // pass below — it is the most specific decision there is.
                    mappings.add(new TransactionMapping(runId, txn.getId(), null,
                            TransactionMapping.Status.EXCLUDED_ONCE, excludedOnce.get(txn.getId())));
                    continue;
                }
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
                    hintMatched.put(txn.getId(), txn);
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
        int cached = applyRememberedCategories(mappings, parkedTransactions, hintMatched, validEntryIds);

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
                case EXCLUDED, EXCLUDED_ONCE -> excluded++;
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
     * Resolve transactions from the merchant cache, removing the ones it handles from
     * {@code parkedTransactions} so the AI pass never sees them. A cached answer
     * pointing at a since-deleted budget entry is dropped (the row is purged), leaving
     * the transaction parked.
     *
     * <p><b>Hint-matched rows are considered too, but only a {@code MANUAL} rule may
     * override them.</b> This pass used to skip everything that was not {@code PARKED},
     * which meant a remembered decision about a merchant the hint pass could match was
     * written and never read back — so excluding, re-categorizing or parking such a row
     * during review was silently undone by the next re-map, and the month's total moved
     * with it. {@code exclude()} promises the opposite in its own javadoc.
     *
     * <p>An {@code AI} answer deliberately does <i>not</i> override a hint: the pattern
     * is the user's own explicit rule and outranks a guess. Only a decision the user
     * made by hand is more specific than a pattern they wrote by hand.
     */
    private int applyRememberedCategories(List<TransactionMapping> mappings,
                                          Map<Long, Transaction> parkedTransactions,
                                          Map<Long, Transaction> hintMatched,
                                          Set<Long> validEntryIds) {
        int applied = 0;
        for (TransactionMapping mapping : mappings) {
            boolean parked = mapping.getStatus() == TransactionMapping.Status.PARKED;
            boolean hinted = mapping.getStatus() == TransactionMapping.Status.MAPPED_HINT;
            if (!parked && !hinted) {
                continue;
            }
            Transaction txn = parked ? parkedTransactions.get(mapping.getTransactionId())
                                     : hintMatched.get(mapping.getTransactionId());
            if (txn == null) {
                continue;
            }
            String key = HintMatcher.normalize(txn.getDescription());
            Optional<MerchantCategory> remembered = merchantCategoryRepository.findByMerchantKey(key);
            if (remembered.isEmpty()) {
                continue;
            }
            MerchantCategory mc = remembered.get();
            boolean manual = mc.getSource() == MerchantCategory.Source.MANUAL;
            if (hinted && !manual) {
                continue; // an AI guess never beats the user's own pattern
            }
            if (mc.isExcluded()) {
                // A remembered "not spend" decision (card payment, deposit, transfer).
                mapping.setBudgetEntryId(null);
                mapping.setStatus(TransactionMapping.Status.EXCLUDED);
                mapping.setReason("Remembered — you excluded this from spend");
                parkedTransactions.remove(mapping.getTransactionId());
                continue; // counted as excluded by the final tally, not as a categorization
            }
            if (!validEntryIds.contains(mc.getBudgetEntryId())) {
                merchantCategoryRepository.delete(mc); // entry gone; forget the stale answer
                continue;
            }
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
                    sample != null ? sample.getDescription() : null, s.reason(), false);
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
                          String sampleDescription, String reason, boolean excluded) {
        MerchantCategory existing = merchantCategoryRepository.findByMerchantKey(merchantKey).orElse(null);
        if (existing != null && source == MerchantCategory.Source.AI
                && existing.getSource() == MerchantCategory.Source.MANUAL) {
            return; // never let AI clobber a human correction
        }
        MerchantCategory mc = existing != null ? existing
                : new MerchantCategory(merchantKey, null, source, sampleDescription, reason, null);
        mc.setBudgetEntryId(budgetEntryId);
        mc.setExcluded(excluded);
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
                        txn != null ? txn.getDescription() : null, "Categorized by hand", false);
            }
        }
        mappingRepository.save(mapping);
        recount(runId);
        return mapping;
    }

    /**
     * Mark one transaction as not-spend within this run, and remember the
     * decision so the same merchant auto-excludes on every future run (card
     * payments, deposits, account transfers). Distinct from parking: excluded
     * money never counts toward the month's spend.
     */
    @Transactional
    public TransactionMapping exclude(Long runId, Long transactionId) {
        TransactionMapping mapping = mappingRepository.findByAnalysisRunIdAndTransactionId(runId, transactionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Transaction " + transactionId + " is not part of run " + runId));

        mapping.setBudgetEntryId(null);
        mapping.setStatus(TransactionMapping.Status.EXCLUDED);
        mapping.setReason("Excluded from spend by hand");
        mappingRepository.save(mapping);

        transactionRepository.findById(transactionId).ifPresent(txn ->
                remember(HintMatcher.normalize(txn.getDescription()), null,
                        MerchantCategory.Source.MANUAL, txn.getDescription(),
                        "Excluded from spend", true));

        recount(runId);
        return mapping;
    }

    /**
     * Exclude <b>this one transaction only</b>, deliberately creating no rule: the
     * merchant cache is not written, so the same merchant still counts as spend
     * next month. For genuine one-time exceptions — a trip paid for out of gift
     * money, a reimbursed purchase — where {@link #exclude} would wrongly teach the
     * app to drop that merchant forever.
     *
     * <p>Because there is no rule to rebuild it from, {@code doMap} carries this
     * decision across a re-map explicitly; see the top of that method.
     *
     * <p>Any standing exclusion previously remembered for this merchant is left
     * alone — un-remembering is what {@link #assign} with a null entry is for.
     */
    @Transactional
    public TransactionMapping excludeOnce(Long runId, Long transactionId) {
        TransactionMapping mapping = mappingRepository.findByAnalysisRunIdAndTransactionId(runId, transactionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Transaction " + transactionId + " is not part of run " + runId));

        mapping.setBudgetEntryId(null);
        mapping.setStatus(TransactionMapping.Status.EXCLUDED_ONCE);
        mapping.setReason("Excluded this one only — no rule created");
        mappingRepository.save(mapping);

        recount(runId);
        return mapping;
    }

    /**
     * Keep the run's counts in step after a manual change.
     *
     * <p>Public because re-ingest also disturbs them: it replaces a statement's
     * transactions and carries the surviving mappings onto the new rows, which can drop
     * any line the re-parse no longer produces.
     */
    public void recount(Long runId) {
        int mapped = 0;
        int parked = 0;
        int excluded = 0;
        for (TransactionMapping m : mappingRepository.findByAnalysisRunId(runId)) {
            switch (m.getStatus()) {
                case PARKED -> parked++;
                case EXCLUDED, EXCLUDED_ONCE -> excluded++;
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
