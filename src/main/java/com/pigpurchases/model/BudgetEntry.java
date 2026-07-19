package com.pigpurchases.model;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "budget_entries")
public class BudgetEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String name;
    private BigDecimal monthlyAllowance;
    private Integer quantity = 1;
    @Column(length = 1024)
    private String hints = "";

    public BudgetEntry() {}

    public BudgetEntry(String name, String category, BigDecimal monthlyAllowance) {
        this.name = name;
        this.monthlyAllowance = monthlyAllowance;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategory() {
        return name;
    }

    public void setCategory(String category) {
        this.name = category;
    }

    public BigDecimal getMonthlyAllowance() {
        return monthlyAllowance;
    }

    public void setMonthlyAllowance(BigDecimal monthlyAllowance) {
        this.monthlyAllowance = monthlyAllowance;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public String getHints() {
        return hints;
    }

    public void setHints(String hints) {
        this.hints = hints;
    }
}
