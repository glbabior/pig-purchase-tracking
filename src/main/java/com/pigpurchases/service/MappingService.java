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
     * Every run and every manual decision leaves a trail on the Debug screen. Mapping is
     * where a month's numbers are actually decided, and most of what it does is invisible:
     * which pass placed a transaction, what the cache already knew, what the AI was asked.
     * When a total looks wrong, this is the record of how it got that way.
     */
    @Autowired private DebugLogService debugLog;

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

    /**
     * The reason written when the user parks a row by hand, which is what tells a
     * deliberate park apart from the PARKED status every unplaced transaction gets.
     * {@code doMap} carries the deliberate ones across a rebuild; the rest stay free for
     * a newly added hint to claim.
     */
    static final String PARKED_BY_HAND = "Un-categorized by hand";

    /**
     * The reason written when the user assigns THIS transaction to a category by hand.
     *
     * <p>Distinct from the cache's "Remembered — your earlier categorization", which also
     * carries {@code MAPPED_MANUAL} but was applied to a different transaction by pattern.
     * {@code AnalysisService.inSpendBuckets} needs the difference: a money-in row counts in
     * a category only when a human put that exact row there, and testing the status alone
     * let one hand-assignment spread to every future transaction sharing its description.
     */
    static final String ASSIGNED_BY_HAND = "Categorized by hand";

    /** Pass 1's verdict. Replaced below with the run's ACTUAL outcome if nothing else places it. */
    static final String NO_MATCH = "No hint or name matched";
    /** Every pass declined it, AI included — so a hint is the remedy, not another run. */
    static final String NOTHING_PLACED_IT = "No rule matched and the AI did not place it";
    /** Nothing matched and the AI was never asked, which is usually the real explanation. */
    static final String NO_MATCH_AI_OFF = "No rule matched; AI categorization is off";

    /** A decision the user made about one specific transaction, carried across a rebuild. */
    private record CarriedDecision(TransactionMapping.Status status, Long budgetEntryId, String reason) {}

    /**
     * True for a decision that applies to THIS transaction and cannot be rebuilt by any
     * pass, so {@code doMap} has to carry it across the rebuild itself.
     *
     * <p>Three qualify, and the third was missed until it cost money. A one-off exclusion
     * writes no merchant rule by design. A deliberate park writes none either — it deletes
     * one. And a hand-assigned row <i>does</i> write a rule, but the rule is keyed on the
     * merchant, not the row: pass 2 rebuilds it as {@code MAPPED_MANUAL} with the reason
     * "Remembered — your earlier categorization", which is exactly what
     * {@code AnalysisService.inSpendBuckets} refuses to treat as a per-row decision. So a
     * refund the user had put into a category silently left it again on the next re-map —
     * including the re-map ingest performs by itself — and that category's total rose.
     *
     * <p>The reason string is the discriminator in two of the three cases because the
     * status alone cannot tell a decision about this row from a pattern that happened to
     * match it.
     */
    private static boolean isPerTransactionDecision(TransactionMapping m) {
        if (m.getStatus() == TransactionMapping.Status.EXCLUDED_ONCE) {
            return true;
        }
        if (m.getStatus() == TransactionMapping.Status.PARKED) {
            return PARKED_BY_HAND.equals(m.getReason());
        }
        return m.getStatus() == TransactionMapping.Status.MAPPED_MANUAL
                && ASSIGNED_BY_HAND.equals(m.getReason());
    }

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

        // Per-transaction decisions with no rule behind them. Unlike every other manual
        // choice they cannot be rebuilt from the merchant cache, so they are carried over
        // the rebuild explicitly — otherwise re-running a statement silently undoes them
        // and the month's total moves with no user action.
        //
        // Two shapes qualify. A one-off exclusion writes no rule by design. And parking a
        // row by hand writes no rule either — worse, it DELETES any the merchant had — so a
        // hand-parked hint match was reclaimed by the very hint the user had just taken it
        // off, putting its amount back into that category. Only a deliberate park counts:
        // PARKED is also the status of everything no pass could place, and those must stay
        // free for a newly added hint to claim.
        Map<Long, CarriedDecision> carried = new LinkedHashMap<>();
        for (TransactionMapping prior : mappingRepository.findByAnalysisRunId(runId)) {
            if (isPerTransactionDecision(prior)) {
                carried.put(prior.getTransactionId(), new CarriedDecision(
                        prior.getStatus(), prior.getBudgetEntryId(), prior.getReason()));
            }
        }

        // Flush the removals before inserting the replacements: Hibernate orders
        // inserts ahead of deletes within a flush, which would otherwise trip the
        // (run, transaction) unique constraint when re-running a run.
        mappingRepository.deleteByAnalysisRunId(runId);
        mappingRepository.flush();

        List<BudgetEntry> entries = budgetEntryRepository.findAll();
        HintMatcher matcher = new HintMatcher(entries);

        // Needed before the loop below, not just by pass 2: a carried decision skips every
        // pass, so without checking it here a hand-assigned row would keep pointing at a
        // budget entry that no longer exists — reintroducing the dangling reference whose
        // whole point was that the money silently leaves every total.
        Set<Long> validEntryIds = new HashSet<>();
        for (BudgetEntry entry : entries) {
            validEntryIds.add(entry.getId());
        }

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
                CarriedDecision decision = carried.get(txn.getId());
                if (decision != null && decision.budgetEntryId() != null
                        && !validEntryIds.contains(decision.budgetEntryId())) {
                    decision = null; // the category is gone; let the passes place it afresh
                }
                if (decision != null) {
                    // The user's call on this exact transaction outranks every pass below —
                    // it is the most specific decision there is. Deliberately not added to
                    // parkedTransactions either, so neither the merchant cache nor the AI
                    // can reclaim something the user just set aside. The entry id rides
                    // along so a hand-assigned row keeps its category, not just its status.
                    mappings.add(new TransactionMapping(runId, txn.getId(), decision.budgetEntryId(),
                            decision.status(), decision.reason()));
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
                            TransactionMapping.Status.PARKED, NO_MATCH);
                    mappings.add(parkedMapping);
                    parkedTransactions.put(txn.getId(), txn);
                }
            }
        }

        int carriedCount = carried.size();
        // Both counted BEFORE pass 2, which is why neither can be reported as a final tally:
        // pass 2 overrides some hint matches with a MANUAL cache answer, so reporting
        // hintMatched.size() double-counted those rows against `cached`, and pass 2 reads
        // hint-matched rows too, so parkedTransactions.size() understated what reached it.
        int reachedPassTwo = parkedTransactions.size() + hintMatched.size();

        // Pass 2 — remembered answers. Every parked transaction whose merchant was
        // categorized on a previous run (by AI, or by the user during review) is
        // resolved from the cache, for free. This is what stops re-runs re-paying.
        int cached = applyRememberedCategories(mappings, parkedTransactions, hintMatched, validEntryIds);

        // Pass 3 — AI, on whatever's left. Its answers are written back to the cache
        // so this is the only run that pays for them. Skipped silently with no
        // credentials, leaving those transactions parked.
        int aiMapped = applyAiSuggestions(mappings, parkedTransactions, entries, validEntryIds);

        // Record what ACTUALLY happened to anything still parked.
        //
        // Pass 1 writes "No hint or name matched", which is true of pass 1 and misleading as
        // a final verdict: a row still parked here was also declined by the merchant cache
        // and by the AI, or the AI never ran. Leaving pass 1's wording made the screen
        // suggest the AI would place a transaction the AI had just passed on — and gave no
        // hint that the real remedy is a hint, or that AI is switched off.
        boolean aiRan = aiCategorizationService.isAvailable();
        for (TransactionMapping mapping : mappings) {
            if (mapping.getStatus() == TransactionMapping.Status.PARKED
                    && NO_MATCH.equals(mapping.getReason())) {
                mapping.setReason(aiRan ? NOTHING_PLACED_IT : NO_MATCH_AI_OFF);
            }
        }

        int mapped = 0;
        int parked = 0;
        int excluded = 0;
        // Counted from the FINAL statuses, so the figures reported below add up to the row
        // count. Snapshotting them per pass double-counted every hint match that pass 2
        // later overrode with a remembered answer.
        int byHint = 0;
        int byManual = 0;
        int byAi = 0;
        for (TransactionMapping mapping : mappings) {
            switch (mapping.getStatus()) {
                case PARKED -> parked++;
                case EXCLUDED, EXCLUDED_ONCE -> excluded++;
                case MAPPED_HINT -> { byHint++; mapped++; }
                case MAPPED_MANUAL -> { byManual++; mapped++; }
                case MAPPED_AI -> { byAi++; mapped++; }
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

        // Which pass placed what. Without this the Debug screen recorded only the outbound
        // API calls, so a run that categorized nothing looked the same whether the hints
        // matched everything, the cache answered everything, or the AI never ran.
        debugLog.info("mapping", "Mapped " + describe(run) + ": " + mappings.size()
                + " transaction(s) — " + byHint + " by hint, " + byManual + " by your own"
                + " decisions or remembered answers, " + byAi + " by AI, " + parked + " parked, "
                + excluded + " not spend"
                + (carriedCount > 0 ? " (" + carriedCount + " kept from decisions you made"
                                            + " about specific transactions)" : "")
                + ". " + reachedPassTwo + " row(s) reached the remembered-answer pass, which"
                + " resolved " + cached + ".");

        return new MapResult(runId, run.getMonth(), mapped, parked, excluded, aiMapped, cached);
    }

    /**
     * Record a manual decision against the transaction it was made about.
     *
     * <p>These are the choices that outrank every automatic pass and, for a one-off
     * exclusion or a deliberate park, the ones with no rule behind them to explain later.
     * A month total that moved between two glances is usually one of these.
     */
    private void logDecision(Long transactionId, String what) {
        String description = transactionRepository.findById(transactionId)
                .map(Transaction::getDescription).orElse("transaction " + transactionId);
        debugLog.info("mapping", "You " + what + ": " + description);
    }

    /** A run named the way the user sees it: the statement, not the internal token. */
    private String describe(AnalysisRun run) {
        return runSourceRepository.findByAnalysisRunId(run.getId()).stream()
                .findFirst()
                .flatMap(link -> importRepository.findById(link.getStatementImportId()))
                .map(imp -> imp.getFileName() + " (" + imp.getStatementDate() + ")")
                .orElse("run " + run.getId());
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
            // Reversing a ONE-OFF exclusion must touch no rule. excludeOnce deliberately
            // wrote nothing, so any merchant_categories row here belongs to some earlier,
            // unrelated decision — deleting it forgot that a merchant meant a category
            // everywhere, for every month, because of one transaction the user set aside.
            boolean wasOneOff = mapping.getStatus() == TransactionMapping.Status.EXCLUDED_ONCE;

            mapping.setBudgetEntryId(null);
            mapping.setStatus(TransactionMapping.Status.PARKED);
            // This one method serves two intents, and only one of them is a decision worth
            // carrying across a re-map. Parking a CATEGORIZED row says "not this category" —
            // deliberate, so doMap must not let a hint reclaim it. Undoing a one-off
            // exclusion says "never mind", which means back to the default: it must stay
            // free for the passes to place, or reversing an exclusion pinned the row in
            // "Other" permanently, unreachable by any hint, cache answer or AI pass ever
            // again. Only the first gets PARKED_BY_HAND.
            mapping.setReason(wasOneOff ? "One-off exclusion reversed" : PARKED_BY_HAND);
            // Deliberately parking a *categorized* row means "this was wrong" — forget the
            // remembered answer. Parking a one-off exclusion means only "never mind".
            if (merchantKey != null && !wasOneOff) {
                merchantCategoryRepository.findByMerchantKey(merchantKey)
                        .ifPresent(merchantCategoryRepository::delete);
            }
        } else {
            BudgetEntry entry = budgetEntryRepository.findById(budgetEntryId)
                    .orElseThrow(() -> new IllegalArgumentException("No such budget entry: " + budgetEntryId));
            mapping.setBudgetEntryId(entry.getId());
            mapping.setStatus(TransactionMapping.Status.MAPPED_MANUAL);
            mapping.setReason(ASSIGNED_BY_HAND);
            if (merchantKey != null) {
                Transaction txn = transactionRepository.findById(transactionId).orElse(null);
                remember(merchantKey, entry.getId(), MerchantCategory.Source.MANUAL,
                        txn != null ? txn.getDescription() : null, ASSIGNED_BY_HAND, false);
            }
        }
        mappingRepository.save(mapping);
        recount(runId);
        logDecision(transactionId, budgetEntryId == null
                ? "un-categorized — back to Other"
                : "categorized as \"" + budgetEntryRepository.findById(budgetEntryId)
                        .map(BudgetEntry::getName).orElse("?") + "\", and remembered for that merchant");
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
        logDecision(transactionId, "excluded from spend, and remembered so this merchant is"
                + " excluded on every future run");
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
        logDecision(transactionId, "excluded just this one from spend — no rule created, so"
                + " this merchant still counts normally next time");
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
