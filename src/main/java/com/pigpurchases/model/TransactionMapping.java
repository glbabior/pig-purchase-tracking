package com.pigpurchases.model;

import jakarta.persistence.*;

/**
 * How one transaction was resolved within one {@link AnalysisRun}.
 *
 * Mapping lives here rather than on the transaction itself so that re-running a
 * month replaces only these rows — ingested transactions are never touched, and
 * a re-run is therefore always safe.
 */
@Entity
@Table(name = "transaction_mappings",
       uniqueConstraints = @UniqueConstraint(columnNames = {"analysisRunId", "transactionId"}))
public class TransactionMapping {

    /**
     * MAPPED_HINT/AI/MANUAL carry a budget entry. PARKED means categorization
     * failed — it shows as "Other" and still counts as spend.
     *
     * <p>Two statuses mean "not spend", and the difference is whether a rule was
     * created:
     * <ul>
     *   <li>{@code EXCLUDED} — a <b>standing</b> exclusion. Either the source's
     *       parser rules flagged it as a transfer, or the user excluded the
     *       merchant and that decision was remembered in {@code merchant_categories},
     *       so every future charge from that merchant is excluded too.</li>
     *   <li>{@code EXCLUDED_ONCE} — a <b>one-off</b> exclusion of this single
     *       transaction, deliberately creating no rule. For spend that is genuinely
     *       a one-time exception (a trip paid for with gift money) where the same
     *       merchant should still count normally next month.</li>
     * </ul>
     * Both are recorded for completeness and neither counts toward spend.
     */
    public enum Status {
        MAPPED_HINT, MAPPED_AI, MAPPED_MANUAL, PARKED, EXCLUDED, EXCLUDED_ONCE;

        /** True for both flavours of "not spend", standing and one-off. */
        public boolean isExcluded() {
            return this == EXCLUDED || this == EXCLUDED_ONCE;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long analysisRunId;
    private Long transactionId;
    private Long budgetEntryId; // null for PARKED and both EXCLUDED flavours

    @Enumerated(EnumType.STRING)
    private Status status;

    /** Why the mapper decided this, e.g. the pattern that matched. Shown during review. */
    @Column(length = 512)
    private String reason;

    public TransactionMapping() {}

    public TransactionMapping(Long analysisRunId, Long transactionId, Long budgetEntryId,
                              Status status, String reason) {
        this.analysisRunId = analysisRunId;
        this.transactionId = transactionId;
        this.budgetEntryId = budgetEntryId;
        this.status = status;
        this.reason = reason;
    }

    /**
     * True when this row represents money that counts toward the month's spend.
     *
     * <p>This is the one place the question is answered, so callers ask it here
     * rather than testing the status themselves. A null status — which application
     * code never writes, but a hand-edited or truncated database could hold —
     * counts as spend, on the principle that unattributed money is still real money
     * and should never silently vanish from a total.
     */
    public boolean countsAsSpend() {
        return status == null || !status.isExcluded();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAnalysisRunId() { return analysisRunId; }
    public void setAnalysisRunId(Long analysisRunId) { this.analysisRunId = analysisRunId; }
    public Long getTransactionId() { return transactionId; }
    public void setTransactionId(Long transactionId) { this.transactionId = transactionId; }
    public Long getBudgetEntryId() { return budgetEntryId; }
    public void setBudgetEntryId(Long budgetEntryId) { this.budgetEntryId = budgetEntryId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
