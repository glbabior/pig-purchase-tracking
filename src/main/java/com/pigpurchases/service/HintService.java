package com.pigpurchases.service;

import com.pigpurchases.model.BudgetEntry;
import com.pigpurchases.model.Transaction;
import com.pigpurchases.repository.BudgetEntryRepository;
import com.pigpurchases.repository.TransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the Hints screen needs, over the storage that already exists.
 *
 * <p>A hint lives as a {@code match:} line in {@link BudgetEntry#getHints()}, mixed in with
 * the prose written for the AI pass. That is deliberately unchanged here: no new table, so
 * no migration and no backup written before today becomes unrestorable — a failure this
 * project has already had twice from adding tables.
 *
 * <p>What was missing was never storage. It was that a hint could not be <b>seen</b>. Rules
 * for different categories were invisible to each other, so one could permanently neutralise
 * another with nothing to say so; an unusable rule was discarded in silence; and the only way
 * to find out what a rule would catch was to save it, re-map, and look. All three are
 * answerable from data already on disk.
 */
@Service
public class HintService {

    @Autowired private BudgetEntryRepository entryRepository;
    @Autowired private TransactionRepository transactionRepository;

    /** One {@code match:} rule, with everything the screen needs to judge it. */
    public record HintRow(Long entryId, String entryName, String hint, String problem,
                          int matchCount) {}

    /** A transaction that more than one category claims. */
    public record Conflict(Long transactionId, String date, String description,
                           List<String> claims, boolean parks) {}

    /** What a candidate rule would catch, before it is saved. */
    public record Preview(String hint, String problem, int matchCount,
                          List<String> samples, List<String> alreadyElsewhere) {}

    /**
     * Every rule across every category, with the reason it is ignored (if it is) and how
     * many of your transactions it currently matches.
     *
     * <p>A rule matching zero transactions is the common surprise: it is either wrong, or
     * written for statements not loaded yet.
     */
    @Transactional(readOnly = true)
    public List<HintRow> allHints() {
        List<BudgetEntry> entries = entryRepository.findAll();
        List<Transaction> transactions = transactionRepository.findAll();

        List<HintRow> rows = new ArrayList<>();
        for (BudgetEntry entry : entries) {
            for (String hint : HintMatcher.explicitHints(entry.getHints())) {
                HintMatcher.HintProblem problem = HintMatcher.validate(hint);
                int count = 0;
                if (problem == null) {
                    HintMatcher single = HintMatcher.forSingleHint(entry, hint);
                    for (Transaction txn : transactions) {
                        if (!single.allMatches(txn.getDescription(), txn.getVendor()).isEmpty()) {
                            count++;
                        }
                    }
                }
                rows.add(new HintRow(entry.getId(), entry.getName(), hint,
                        problem == null ? null : problem.problem(), count));
            }
        }
        rows.sort(Comparator.comparing((HintRow r) -> r.entryName() == null ? "" : r.entryName().toLowerCase())
                .thenComparing(HintRow::hint));
        return rows;
    }

    /**
     * Transactions that more than one category's rules claim.
     *
     * <p>Two shapes, and the screen distinguishes them because they behave differently.
     * When the weights TIE the transaction is parked rather than guessed at — a rule you
     * wrote produces nothing and looks broken. When they do not tie the heavier rule wins
     * silently, which is fine until it is not the one you meant.
     */
    @Transactional(readOnly = true)
    public List<Conflict> conflicts() {
        List<BudgetEntry> entries = entryRepository.findAll();
        HintMatcher matcher = new HintMatcher(entries);

        List<Conflict> out = new ArrayList<>();
        for (Transaction txn : transactionRepository.findAll()) {
            List<HintMatcher.Pattern> hits = matcher.allMatches(txn.getDescription(), txn.getVendor());
            if (hits.size() < 2) {
                continue;
            }
            // Only across DIFFERENT entries. Two rules on the same category both matching is
            // ordinary and harmless — that is what having several rules is for.
            Map<Long, String> byEntry = new LinkedHashMap<>();
            int best = hits.stream().mapToInt(HintMatcher.Pattern::weight).max().orElse(0);
            long entriesAtBest = hits.stream()
                    .filter(p -> p.weight() == best)
                    .map(p -> p.entry().getId())
                    .distinct().count();
            for (HintMatcher.Pattern p : hits) {
                byEntry.putIfAbsent(p.entry().getId(),
                        p.entry().getName() + " — \"" + p.display() + "\""
                                + (p.weight() == best ? "" : " (less specific, loses)"));
            }
            if (byEntry.size() < 2) {
                continue;
            }
            out.add(new Conflict(txn.getId(),
                    txn.getTransactionDate() == null ? null : txn.getTransactionDate().toString(),
                    txn.getDescription(), new ArrayList<>(byEntry.values()), entriesAtBest > 1));
        }
        // Ties first: those are the ones actively costing the user a categorization.
        out.sort(Comparator.comparing(Conflict::parks).reversed());
        return out;
    }

    /**
     * What a rule would catch if saved, without saving it.
     *
     * <p>{@code alreadyElsewhere} is the important half: a rule that matches fifty
     * transactions is only good news if none of them are already filed somewhere you meant.
     */
    @Transactional(readOnly = true)
    public Preview preview(Long entryId, String hint) {
        BudgetEntry entry = entryRepository.findById(entryId)
                .orElseThrow(() -> new IllegalArgumentException("No such budget entry: " + entryId));

        HintMatcher.HintProblem problem = HintMatcher.validate(hint);
        if (problem != null) {
            return new Preview(hint, problem.problem(), 0, List.of(), List.of());
        }

        HintMatcher single = HintMatcher.forSingleHint(entry, hint);
        HintMatcher existing = new HintMatcher(entryRepository.findAll());

        List<String> samples = new ArrayList<>();
        List<String> elsewhere = new ArrayList<>();
        int count = 0;
        for (Transaction txn : transactionRepository.findAll()) {
            if (single.allMatches(txn.getDescription(), txn.getVendor()).isEmpty()) {
                continue;
            }
            count++;
            if (samples.size() < 25) {
                samples.add((txn.getTransactionDate() == null ? "" : txn.getTransactionDate() + "  ")
                        + txn.getDescription());
            }
            existing.match(txn.getDescription(), txn.getVendor()).ifPresent(m -> {
                if (!m.entry().getId().equals(entryId) && elsewhere.size() < 25) {
                    elsewhere.add(txn.getDescription() + "  →  currently " + m.entry().getName());
                }
            });
        }
        return new Preview(hint, null, count, samples, elsewhere);
    }
}
