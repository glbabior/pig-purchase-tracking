package com.pigpurchases.model;

import jakarta.persistence.*;
import java.math.BigDecimal;

/**
 * One era of a budget entry's monthly amount: "this category's budget is
 * {@code amount} from {@code startMonth} onward" — until a later era takes over.
 *
 * <p>A {@code null} startMonth means "since the beginning". An entry with <b>no</b>
 * era rows at all means its current {@link BudgetEntry#getMonthlyAllowance() amount}
 * has always applied — which is every entry until the first time its amount is
 * changed "going forward", and every entry in a database restored from a backup
 * taken before eras existed. The first forward change writes two rows at once:
 * the old amount as the since-the-beginning era, and the new amount from the
 * current month. Analysis resolves each calendar month against the era in force
 * for that month, so past months keep the budget they were lived under.
 *
 * <p>{@code startMonth} is a {@code YYYY-MM} string like the other month tokens in
 * this schema, so eras compare correctly as text.
 */
@Entity
@Table(name = "budget_amount_eras")
public class BudgetAmountEra {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long budgetEntryId;

    @Column(name = "start_month")
    private String startMonth;

    private BigDecimal amount;

    public BudgetAmountEra() {}

    public BudgetAmountEra(Long budgetEntryId, String startMonth, BigDecimal amount) {
        this.budgetEntryId = budgetEntryId;
        this.startMonth = startMonth;
        this.amount = amount;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getBudgetEntryId() {
        return budgetEntryId;
    }

    public void setBudgetEntryId(Long budgetEntryId) {
        this.budgetEntryId = budgetEntryId;
    }

    public String getStartMonth() {
        return startMonth;
    }

    public void setStartMonth(String startMonth) {
        this.startMonth = startMonth;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
}
