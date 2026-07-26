package com.pigpurchases.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Whether a calendar month is "complete" — i.e. all of its transactions have been
 * ingested and mapped, so it can safely feed the rolling ("typical month")
 * average. Set explicitly by the user; the analysis only offers a suggestion.
 *
 * <p>New table (not a new column on a populated table), so no migration backfill
 * concerns.
 */
@Entity
@Table(name = "month_status")
public class MonthStatus {

    @Id
    @Column(name = "status_month") // "month" is a reserved word in H2
    private String month; // YYYY-MM

    private boolean complete;

    public MonthStatus() {}

    public MonthStatus(String month, boolean complete) {
        this.month = month;
        this.complete = complete;
    }

    public String getMonth() {
        return month;
    }

    public void setMonth(String month) {
        this.month = month;
    }

    public boolean isComplete() {
        return complete;
    }

    public void setComplete(boolean complete) {
        this.complete = complete;
    }
}
