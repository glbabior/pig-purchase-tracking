package com.pigpurchases.service;

import com.pigpurchases.model.AnalysisRun;
import com.pigpurchases.model.Transaction;
import com.pigpurchases.model.TransactionMapping;
import com.pigpurchases.repository.AnalysisRunRepository;
import com.pigpurchases.repository.BudgetEntryRepository;
import com.pigpurchases.repository.TransactionMappingRepository;
import com.pigpurchases.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

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

    public ManualEntryService(TransactionRepository transactionRepository,
                              TransactionMappingRepository mappingRepository,
                              AnalysisRunRepository runRepository,
                              BudgetEntryRepository budgetEntryRepository) {
        this.transactionRepository = transactionRepository;
        this.mappingRepository = mappingRepository;
        this.runRepository = runRepository;
        this.budgetEntryRepository = budgetEntryRepository;
    }

    /** Existing transactions on the same day for the same amount — the manual-entry dup check. */
    @Transactional(readOnly = true)
    public List<Transaction> findPotentialDuplicates(LocalDate date, BigDecimal amount) {
        BigDecimal target = amount.abs();
        List<Transaction> out = new ArrayList<>();
        for (Transaction t : transactionRepository.findAll()) {
            if (date.equals(t.getTransactionDate()) && t.getAmount() != null
                    && t.getAmount().abs().compareTo(target) == 0) {
                out.add(t);
            }
        }
        return out;
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
