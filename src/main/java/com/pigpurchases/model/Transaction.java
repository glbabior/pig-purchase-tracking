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
    private String month; // YYYY-MM format

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
}
