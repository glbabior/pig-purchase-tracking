package com.pigpurchases.model;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;
import java.time.LocalDateTime;

/**
 * A remembered merchant → budget entry categorization, keyed by the normalized
 * merchant text (the same key {@code HintMatcher.normalize} produces). This is
 * how mapping avoids re-paying the Claude API: once a merchant has been
 * categorized — by the AI, or by the user correcting it during review — the
 * answer is stored here and reused on every later run without another API call.
 *
 * A manual correction always wins over a remembered AI answer, so fixing
 * "FRESHMARKET" once fixes every future Fresh Market line.
 */
@Entity
@Table(name = "merchant_categories")
public class MerchantCategory {

    /** Where the remembered answer came from. MANUAL is authoritative over AI. */
    public enum Source { AI, MANUAL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String merchantKey;

    private Long budgetEntryId;

    /**
     * True for a remembered "not spend" decision (a transfer, deposit, or card
     * payment the user excluded by hand). budgetEntryId is null in that case.
     * The @ColumnDefault is required: this column is added to a merchant_categories
     * table that already has rows, so it needs a DB-level default to backfill them.
     */
    @ColumnDefault("false")
    private boolean excluded;

    @Enumerated(EnumType.STRING)
    private Source source;

    /** A readable example of a transaction that produced this key, for a future review UI. */
    @Column(length = 512)
    private String sampleDescription;

    @Column(length = 512)
    private String reason;

    private LocalDateTime updatedAt;

    public MerchantCategory() {}

    public MerchantCategory(String merchantKey, Long budgetEntryId, Source source,
                            String sampleDescription, String reason, LocalDateTime updatedAt) {
        this.merchantKey = merchantKey;
        this.budgetEntryId = budgetEntryId;
        this.source = source;
        this.sampleDescription = sampleDescription;
        this.reason = reason;
        this.updatedAt = updatedAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getMerchantKey() { return merchantKey; }
    public void setMerchantKey(String merchantKey) { this.merchantKey = merchantKey; }
    public Long getBudgetEntryId() { return budgetEntryId; }
    public void setBudgetEntryId(Long budgetEntryId) { this.budgetEntryId = budgetEntryId; }
    public boolean isExcluded() { return excluded; }
    public void setExcluded(boolean excluded) { this.excluded = excluded; }
    public Source getSource() { return source; }
    public void setSource(Source source) { this.source = source; }
    public String getSampleDescription() { return sampleDescription; }
    public void setSampleDescription(String sampleDescription) { this.sampleDescription = sampleDescription; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
