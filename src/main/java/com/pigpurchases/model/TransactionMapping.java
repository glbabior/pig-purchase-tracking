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
     * failed — it shows as "Other" and still counts as spend. EXCLUDED means the
     * source's parser rules flagged it as a transfer: recorded for completeness,
     * never counted, never categorized.
     */
    public enum Status { MAPPED_HINT, MAPPED_AI, MAPPED_MANUAL, PARKED, EXCLUDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long analysisRunId;
    private Long transactionId;
    private Long budgetEntryId; // null for PARKED and EXCLUDED

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

    /** True when this row represents money that counts toward the month's spend. */
    public boolean countsAsSpend() {
        return status != Status.EXCLUDED;
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
