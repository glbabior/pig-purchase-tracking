package com.pigpurchases.service;

import com.pigpurchases.model.AnalysisRun;
import com.pigpurchases.model.DismissedDuplicate;
import com.pigpurchases.model.Transaction;
import com.pigpurchases.model.TransactionMapping;
import com.pigpurchases.repository.AnalysisRunRepository;
import com.pigpurchases.repository.BudgetEntryRepository;
import com.pigpurchases.repository.DismissedDuplicateRepository;
import com.pigpurchases.repository.TransactionMappingRepository;
import com.pigpurchases.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manually-entered transactions — spend that never hits a bank statement (e.g. a
 * Venmo balance). They're stored as ordinary {@link Transaction}s with no
 * statement import/source (so the Ingest screen stays about files), and their
 * mapping hangs off one hidden "Manual" run so the analysis counts them by actual
 * date and they remain reassignable like anything else.
 */
@Service
public class ManualEntryService {

    /** Sentinel run month for the singleton run that owns manual mappings; hidden from the Mapping screen. */
    public static final String MANUAL_MONTH = "manual";

    private final TransactionRepository transactionRepository;
    private final TransactionMappingRepository mappingRepository;
    private final AnalysisRunRepository runRepository;
    private final BudgetEntryRepository budgetEntryRepository;
    private final DismissedDuplicateRepository dismissedDuplicateRepository;

    public ManualEntryService(TransactionRepository transactionRepository,
                              TransactionMappingRepository mappingRepository,
                              AnalysisRunRepository runRepository,
                              BudgetEntryRepository budgetEntryRepository,
                              DismissedDuplicateRepository dismissedDuplicateRepository) {
        this.transactionRepository = transactionRepository;
        this.mappingRepository = mappingRepository;
        this.runRepository = runRepository;
        this.budgetEntryRepository = budgetEntryRepository;
        this.dismissedDuplicateRepository = dismissedDuplicateRepository;
    }

    /** Existing transactions on the same day for the same amount — the manual-entry dup check.
     *  Excluded ("not spend") transactions are ignored: they're already accounted for elsewhere. */
    @Transactional(readOnly = true)
    public List<Transaction> findPotentialDuplicates(LocalDate date, BigDecimal amount) {
        BigDecimal target = amount.abs();
        Set<Long> excluded = excludedTransactionIds();
        List<Transaction> out = new ArrayList<>();
        for (Transaction t : transactionRepository.findAll()) {
            if (excluded.contains(t.getId())) {
                continue;
            }
            if (date.equals(t.getTransactionDate()) && t.getAmount() != null
                    && t.getAmount().abs().compareTo(target) == 0) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * Transactions that don't count as spend — mapping marked EXCLUDED, or the
     * parser flagged them as a transfer. These are skipped by the duplicate checks
     * since an excluded item is already accounted for and isn't a spend duplicate.
     */
    private Set<Long> excludedTransactionIds() {
        Set<Long> ids = new HashSet<>();
        for (TransactionMapping m : mappingRepository.findAll()) {
            if (m.getStatus() == TransactionMapping.Status.EXCLUDED) {
                ids.add(m.getTransactionId());
            }
        }
        for (Transaction t : transactionRepository.findAll()) {
            if (t.isExcludeFromSpend()) {
                ids.add(t.getId());
            }
        }
        return ids;
    }

    @Transactional
    public Transaction addManual(LocalDate date, String vendor, BigDecimal amount, String description,
                                 Long budgetEntryId, boolean excluded) {
        if (date == null) {
            throw new IllegalArgumentException("A date is required.");
        }
        if (amount == null || amount.signum() == 0) {
            throw new IllegalArgumentException("A non-zero amount is required.");
        }
        Long entryId = excluded ? null : budgetEntryId;
        if (entryId != null && budgetEntryRepository.findById(entryId).isEmpty()) {
            throw new IllegalArgumentException("Unknown budget entry.");
        }

        Transaction txn = new Transaction(date, description == null ? "" : description,
                vendor == null ? "" : vendor, amount.abs(), YearMonth.from(date).toString());
        txn.setType("PURCHASE"); // manual entries are money-out; "Not spend" handles exclusions
        txn.setExcludeFromSpend(excluded);
        txn = transactionRepository.save(txn);

        AnalysisRun manualRun = getOrCreateManualRun();
        TransactionMapping.Status status = excluded ? TransactionMapping.Status.EXCLUDED
                : (entryId != null ? TransactionMapping.Status.MAPPED_MANUAL : TransactionMapping.Status.PARKED);
        mappingRepository.save(new TransactionMapping(manualRun.getId(), txn.getId(), entryId, status, "Manual entry"));
        return txn;
    }

    /**
     * Groups of transactions that share the same date and the same absolute amount
     * — potential duplicates (e.g. the same purchase appearing in two overlapping
     * statements, or a manual entry that also landed on a statement). Only groups
     * with more than one transaction are returned; each group is date-ordered.
     */
    @Transactional(readOnly = true)
    public List<List<Transaction>> findDuplicateGroups() {
        Set<Long> excluded = excludedTransactionIds();
        Map<String, List<Transaction>> byKey = new LinkedHashMap<>();
        for (Transaction t : transactionRepository.findAll()) {
            if (t.getTransactionDate() == null || t.getAmount() == null || excluded.contains(t.getId())) {
                continue; // skip excluded ("not spend") items — already accounted for
            }
            String key = t.getTransactionDate() + "|" + t.getAmount().abs().toPlainString();
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
        }
        List<List<Transaction>> groups = new ArrayList<>();
        for (List<Transaction> g : byKey.values()) {
            if (g.size() > 1 && !dismissedDuplicateRepository.existsBySignature(signatureOf(g))) {
                groups.add(g);
            }
        }
        return groups;
    }

    /** Record that a group is NOT a duplicate, so it stops being offered. */
    @Transactional
    public void dismissDuplicateGroup(List<Long> transactionIds) {
        String signature = signatureOfIds(transactionIds);
        if (!signature.isEmpty() && !dismissedDuplicateRepository.existsBySignature(signature)) {
            dismissedDuplicateRepository.save(new DismissedDuplicate(signature));
        }
    }

    /** Stable signature of a group: its transaction ids, sorted and comma-joined. */
    private String signatureOf(List<Transaction> group) {
        List<Long> ids = new ArrayList<>();
        for (Transaction t : group) {
            ids.add(t.getId());
        }
        return signatureOfIds(ids);
    }

    private String signatureOfIds(List<Long> ids) {
        return ids.stream().filter(java.util.Objects::nonNull).sorted()
                .map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    }

    /** Remove a transaction and any mapping(s) for it. */
    @Transactional
    public void deleteTransaction(Long transactionId) {
        mappingRepository.deleteByTransactionId(transactionId);
        transactionRepository.deleteById(transactionId);
    }

    private AnalysisRun getOrCreateManualRun() {
        return runRepository.findByMonth(MANUAL_MONTH).orElseGet(() -> {
            AnalysisRun run = new AnalysisRun(MANUAL_MONTH, LocalDateTime.now());
            run.setStatus(AnalysisRun.Status.MAPPED);
            return runRepository.save(run);
        });
    }
}
