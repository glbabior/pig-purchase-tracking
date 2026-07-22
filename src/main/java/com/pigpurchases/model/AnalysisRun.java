package com.pigpurchases.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * One month's analysis: a user-asserted month bound to exactly one ingested
 * statement per statement source (see {@link AnalysisRunSource}).
 *
 * The month recorded here is authoritative — it is the only thing that assigns a
 * transaction to a month. Transaction dates, the ingest-time txn_month, and the
 * statement's own date are all irrelevant, because billing cycles differ per
 * account (a statement dated July can carry June's charges).
 */
@Entity
@Table(name = "analysis_runs")
public class AnalysisRun {

    /** DRAFT: sources chosen, mapping not run yet. MAPPED: mapping has been executed. */
    public enum Status { DRAFT, MAPPED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_month", nullable = false, unique = true) // "month" is reserved in H2
    private String month; // YYYY-MM

    private LocalDateTime createdAt;
    private LocalDateTime mappedAt;

    @Enumerated(EnumType.STRING)
    private Status status = Status.DRAFT;

    private int mappedCount;
    private int parkedCount;
    private int excludedCount;

    public AnalysisRun() {}

    public AnalysisRun(String month, LocalDateTime createdAt) {
        this.month = month;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getMonth() { return month; }
    public void setMonth(String month) { this.month = month; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getMappedAt() { return mappedAt; }
    public void setMappedAt(LocalDateTime mappedAt) { this.mappedAt = mappedAt; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public int getMappedCount() { return mappedCount; }
    public void setMappedCount(int mappedCount) { this.mappedCount = mappedCount; }
    public int getParkedCount() { return parkedCount; }
    public void setParkedCount(int parkedCount) { this.parkedCount = parkedCount; }
    public int getExcludedCount() { return excludedCount; }
    public void setExcludedCount(int excludedCount) { this.excludedCount = excludedCount; }
}
