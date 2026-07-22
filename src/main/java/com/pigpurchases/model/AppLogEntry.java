package com.pigpurchases.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * One line in the in-app debug log — the record behind the Debug screen.
 *
 * These are deliberately persisted (not just console output) so a problem that
 * happens during a run, or before you're looking at the terminal, is still there
 * to inspect afterwards. Old entries are pruned past the configured retention
 * window (see {@code AppSettings.debugLogRetentionDays}).
 */
@Entity
@Table(name = "app_log_entries")
public class AppLogEntry {

    public enum Level { INFO, WARN, ERROR }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDateTime createdAt;

    @Enumerated(EnumType.STRING)
    private Level level;

    /** A coarse grouping, e.g. "anthropic" for outbound API calls, "mapping" for run activity. */
    private String category;

    @Column(length = 4000)
    private String message;

    public AppLogEntry() {}

    public AppLogEntry(LocalDateTime createdAt, Level level, String category, String message) {
        this.createdAt = createdAt;
        this.level = level;
        this.category = category;
        this.message = message;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public Level getLevel() { return level; }
    public void setLevel(Level level) { this.level = level; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
