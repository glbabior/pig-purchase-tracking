package com.pigpurchases.model;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;
import java.math.BigDecimal;

/**
 * Application-wide settings. Stored as a single row (id = 1). The monthly
 * allowance shown in the UI is derived (annualBudget / 12), not stored.
 */
@Entity
@Table(name = "app_settings")
public class AppSettings {
    @Id
    private Long id = 1L;

    private BigDecimal annualBudget = BigDecimal.ZERO;

    /**
     * How many days the in-app debug log is kept before entries are pruned.
     * The {@code @ColumnDefault} is required, not cosmetic: without a DB-level
     * default, adding this NOT NULL column to an app_settings table that already
     * has a row fails the schema migration (H2 can't backfill the existing row).
     */
    @ColumnDefault("2")
    private int debugLogRetentionDays = 2;

    public AppSettings() {}

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public BigDecimal getAnnualBudget() {
        return annualBudget;
    }

    public void setAnnualBudget(BigDecimal annualBudget) {
        this.annualBudget = annualBudget;
    }

    public int getDebugLogRetentionDays() {
        return debugLogRetentionDays;
    }

    public void setDebugLogRetentionDays(int debugLogRetentionDays) {
        this.debugLogRetentionDays = debugLogRetentionDays;
    }
}
