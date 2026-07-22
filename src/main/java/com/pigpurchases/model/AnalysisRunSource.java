package com.pigpurchases.model;

import jakarta.persistence.*;

/**
 * The statement a given source contributes to one {@link AnalysisRun} — exactly
 * one row per source per run.
 *
 * These rows are also what makes a statement "consumed": a statement import
 * referenced here is not offered when setting up a later run, which is what
 * stops one statement being counted into two months. Consumption is derived from
 * these rows rather than flagged on the import, so it cannot drift.
 */
@Entity
// One statement per source per run is a hard invariant. Reuse of a statement
// across runs is deliberately NOT constrained here: it is hidden by default but
// permitted when the user explicitly asks for it, so it cannot be a DB rule.
@Table(name = "analysis_run_sources",
       uniqueConstraints = @UniqueConstraint(columnNames = {"analysisRunId", "statementSourceId"}))
public class AnalysisRunSource {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long analysisRunId;
    private Long statementSourceId;
    private Long statementImportId;

    public AnalysisRunSource() {}

    public AnalysisRunSource(Long analysisRunId, Long statementSourceId, Long statementImportId) {
        this.analysisRunId = analysisRunId;
        this.statementSourceId = statementSourceId;
        this.statementImportId = statementImportId;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAnalysisRunId() { return analysisRunId; }
    public void setAnalysisRunId(Long analysisRunId) { this.analysisRunId = analysisRunId; }
    public Long getStatementSourceId() { return statementSourceId; }
    public void setStatementSourceId(Long statementSourceId) { this.statementSourceId = statementSourceId; }
    public Long getStatementImportId() { return statementImportId; }
    public void setStatementImportId(Long statementImportId) { this.statementImportId = statementImportId; }
}
