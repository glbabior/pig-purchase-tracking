package com.pigpurchases.model;

import jakarta.persistence.*;
import java.math.BigDecimal;

/**
 * One era of the annual budget, mirroring {@link BudgetAmountEra}: the annual
 * budget is {@code amount} from {@code startMonth} (a {@code YYYY-MM} token,
 * {@code null} = since the beginning) until a later era takes over.
 *
 * <p>No rows means the current {@link AppSettings#getAnnualBudget() annual budget}
 * has always applied. Eras are written only by an explicit change in Settings —
 * never as a side effect of a category change — so the unallocated "Other" budget
 * line for a past month is computed from the annual budget in force <i>that</i>
 * month against that month's category budgets.
 */
@Entity
@Table(name = "annual_budget_eras")
public class AnnualBudgetEra {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "start_month")
    private String startMonth;

    private BigDecimal amount;

    public AnnualBudgetEra() {}

    public AnnualBudgetEra(String startMonth, BigDecimal amount) {
        this.startMonth = startMonth;
        this.amount = amount;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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
