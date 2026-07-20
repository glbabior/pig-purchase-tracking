package com.pigpurchases.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "transactions")
public class Transaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDate transactionDate;
    private String description;
    private String vendor;
    private BigDecimal amount;

    @Column(name = "txn_month") // "month" is a reserved word in H2
    private String month; // YYYY-MM format

    private Long statementImportId;   // the ingest batch this came from
    private Long statementSourceId;   // denormalized for convenient querying
    private String type;              // ParsedTransaction.Type name (PURCHASE, WITHDRAWAL, ...)
    private boolean excludeFromSpend; // transfers etc.: kept, but not counted as spend

    @ManyToOne
    @JoinColumn(name = "budget_entry_id")
    private BudgetEntry budgetEntry;

    @Column(length = 1024)
    private String notes = "";

    public Transaction() {}

    public Transaction(LocalDate transactionDate, String description, String vendor, BigDecimal amount, String month) {
        this.transactionDate = transactionDate;
        this.description = description;
        this.vendor = vendor;
        this.amount = amount;
        this.month = month;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public LocalDate getTransactionDate() {
        return transactionDate;
    }

    public void setTransactionDate(LocalDate transactionDate) {
        this.transactionDate = transactionDate;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getVendor() {
        return vendor;
    }

    public void setVendor(String vendor) {
        this.vendor = vendor;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getMonth() {
        return month;
    }

    public void setMonth(String month) {
        this.month = month;
    }

    public BudgetEntry getBudgetEntry() {
        return budgetEntry;
    }

    public void setBudgetEntry(BudgetEntry budgetEntry) {
        this.budgetEntry = budgetEntry;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Long getStatementImportId() { return statementImportId; }
    public void setStatementImportId(Long statementImportId) { this.statementImportId = statementImportId; }

    public Long getStatementSourceId() { return statementSourceId; }
    public void setStatementSourceId(Long statementSourceId) { this.statementSourceId = statementSourceId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public boolean isExcludeFromSpend() { return excludeFromSpend; }
    public void setExcludeFromSpend(boolean excludeFromSpend) { this.excludeFromSpend = excludeFromSpend; }
}
