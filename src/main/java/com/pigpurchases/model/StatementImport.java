package com.pigpurchases.model;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Records one ingested statement file for a source: its statement date, the
 * file it came from, when it was imported, and how many transactions it yielded.
 * Powers the ingest-history view and idempotent re-ingest (one import per
 * source + statement date).
 */
@Entity
@Table(name = "statement_imports")
public class StatementImport {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long statementSourceId;
    private LocalDate statementDate;
    private String fileName;
    @Column(length = 1024)
    private String relativePath; // path under the source folder, so the exact file is locatable
    private LocalDateTime importedAt;
    private int transactionCount;

    public StatementImport() {}

    public StatementImport(Long statementSourceId, LocalDate statementDate, String fileName,
                           String relativePath, LocalDateTime importedAt, int transactionCount) {
        this.statementSourceId = statementSourceId;
        this.statementDate = statementDate;
        this.fileName = fileName;
        this.relativePath = relativePath;
        this.importedAt = importedAt;
        this.transactionCount = transactionCount;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStatementSourceId() { return statementSourceId; }
    public void setStatementSourceId(Long statementSourceId) { this.statementSourceId = statementSourceId; }
    public LocalDate getStatementDate() { return statementDate; }
    public void setStatementDate(LocalDate statementDate) { this.statementDate = statementDate; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getRelativePath() { return relativePath; }
    public void setRelativePath(String relativePath) { this.relativePath = relativePath; }
    public LocalDateTime getImportedAt() { return importedAt; }
    public void setImportedAt(LocalDateTime importedAt) { this.importedAt = importedAt; }
    public int getTransactionCount() { return transactionCount; }
    public void setTransactionCount(int transactionCount) { this.transactionCount = transactionCount; }
}
