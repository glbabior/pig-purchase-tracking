package com.pigpurchases.service;

import com.pigpurchases.config.EnumColumnMigration;
import com.pigpurchases.config.SwitchableDataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Non-destructive backup restore with a preview step.
 *
 * <p><b>Preview</b> loads the chosen backup into a throwaway preview database and
 * routes the whole app at it (via {@link SwitchableDataSource}), so every screen
 * shows the backup's data for validation. The live database is untouched.
 *
 * <p><b>Commit</b> applies the backup to the live database in place
 * ({@code DROP ALL OBJECTS; RUNSCRIPT}), after taking a rollback snapshot first.
 * <b>Cancel</b> simply drops the preview and routes back to live. A crash or
 * restart mid-preview is safe: the live db was never modified.
 */
@Service
public class RestoreService {

    private static final Logger log = LoggerFactory.getLogger(RestoreService.class);

    private final SwitchableDataSource switchableDataSource;
    private final BackupService backupService;
    private final EnumColumnMigration enumColumnMigration;

    @Value("${pigpurchases.backup.dir:${user.home}/pigpurchases-backups}")
    private String backupDir;
    @Value("${user.home}/.pigpurchases/preview/preview-db")
    private String previewDbBase;

    // Preview state (single-user, in-memory). Cleared on commit/cancel and never
    // survives a restart — which is safe, because the live db is untouched during preview.
    private volatile boolean active = false;
    private volatile String file;
    private volatile String autoValidation;
    private volatile boolean looksHealthy;
    private volatile String reviewerComment;
    private volatile LocalDateTime startedAt;
    private volatile HikariDataSource previewDataSource;

    public RestoreService(SwitchableDataSource switchableDataSource, BackupService backupService,
                          EnumColumnMigration enumColumnMigration) {
        this.switchableDataSource = switchableDataSource;
        this.backupService = backupService;
        this.enumColumnMigration = enumColumnMigration;
    }

    public synchronized Map<String, Object> state() {
        Map<String, Object> m = new HashMap<>();
        m.put("active", active);
        m.put("file", file);
        m.put("autoValidation", autoValidation);
        m.put("looksHealthy", looksHealthy);
        m.put("reviewerComment", reviewerComment);
        m.put("startedAt", startedAt == null ? null : startedAt.toString());
        return m;
    }

    /** Begin previewing a backup: load it into a preview db, validate, and route the app at it. */
    public synchronized Map<String, Object> preview(String fileName) {
        if (active) {
            throw new IllegalStateException("A restore preview is already in progress. Commit or cancel it first.");
        }
        Path backup = resolveBackup(fileName);

        // Snapshot of the current LIVE data, so the validation can report the diff.
        Map<String, Object> liveSummary = summarize(switchableDataSource.getLive());

        // Build a fresh preview database from the backup.
        HikariDataSource preview = null;
        try {
            Path base = Paths.get(previewDbBase);
            Files.createDirectories(base.getParent());
            deletePreviewFiles(base);
            preview = hikari("jdbc:h2:file:" + base.toAbsolutePath().toString().replace("\\", "/"));
            try (Connection c = preview.getConnection(); Statement st = c.createStatement()) {
                st.execute("RUNSCRIPT FROM '" + sqlPath(backup) + "'");
            }
            // The dump rebuilt the schema exactly as that backup was written, so an old
            // enough one brings back an ENUM column that can't hold a newer status. The
            // preview is meant to be traversed, and every screen is writable while it is
            // active, so widen here too — this database is by definition the one with the
            // oldest schema in play.
            enumColumnMigration.migrate(preview);
        } catch (Exception e) {
            if (preview != null) preview.close();
            throw new IllegalStateException("Could not load backup '" + fileName + "': " + e.getMessage(), e);
        }

        // Validate the loaded backup and route the app at it.
        Map<String, Object> previewSummary = summarize(preview);
        previewSummary.put("missingColumns", missingColumns(preview));
        String report = buildValidation(fileName, previewSummary, liveSummary);

        this.previewDataSource = preview;
        this.file = fileName;
        this.autoValidation = report;
        // Auto-generated plain-English recommendation, shown as the reviewer note.
        // A manual note posted via setReviewerComment() overrides this.
        this.reviewerComment = buildReviewerNote(previewSummary, liveSummary);
        this.startedAt = LocalDateTime.now();
        this.active = true;
        switchableDataSource.startPreview(preview);
        log.info("Restore preview started for {} — app is now showing the backup (live db untouched).", fileName);
        return state();
    }

    /** Apply the previewed backup to the live database, in place. */
    public synchronized Map<String, Object> commit() {
        if (!active) {
            throw new IllegalStateException("No restore preview to commit.");
        }
        Path backup = resolveBackup(file);
        DataSource live = switchableDataSource.getLive();

        // Rollback snapshot of the current live db before we overwrite it.
        Path rollback = Paths.get(backupDir).resolve("restore-rollback-"
                + java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss").format(LocalDateTime.now()) + ".sql");
        try (Connection c = live.getConnection(); Statement st = c.createStatement()) {
            st.execute("SCRIPT TO '" + sqlPath(rollback) + "'");
        } catch (SQLException e) {
            throw new IllegalStateException("Aborting commit — could not take a rollback snapshot: " + e.getMessage(), e);
        }

        try (Connection c = live.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP ALL OBJECTS");
            st.execute("RUNSCRIPT FROM '" + sqlPath(backup) + "'");
        } catch (SQLException e) {
            // Best-effort recovery from the rollback snapshot we just took.
            try (Connection c = live.getConnection(); Statement st = c.createStatement()) {
                st.execute("DROP ALL OBJECTS");
                st.execute("RUNSCRIPT FROM '" + sqlPath(rollback) + "'");
                log.error("Commit failed; live database rolled back from {}", rollback.getFileName(), e);
            } catch (SQLException recover) {
                log.error("Commit failed AND rollback failed. Live db may be empty; restore manually from {}",
                        rollback.toAbsolutePath(), recover);
            }
            clearPreview();
            throw new IllegalStateException("Restore commit failed; live database was rolled back. Details: " + e.getMessage(), e);
        }

        // The dump just recreated the schema as it was when that backup was taken, so
        // a backup predating a status would bring back the ENUM column that can't hold
        // it. Re-widen before anything writes.
        enumColumnMigration.migrate(live);

        String restored = file;
        clearPreview();
        // Let the next automatic backup record the restored state as the new normal.
        backupService.resetBaselineAfterRestore();
        log.info("Restore committed: live database replaced with backup {} (rollback saved as {}).",
                restored, rollback.getFileName());
        Map<String, Object> result = new HashMap<>();
        result.put("committed", restored);
        result.put("rollbackFile", rollback.getFileName().toString());
        return result;
    }

    /** Discard the preview and route back to the untouched live database. */
    public synchronized Map<String, Object> cancel() {
        if (!active) {
            throw new IllegalStateException("No restore preview to cancel.");
        }
        String cancelled = file;
        clearPreview();
        log.info("Restore preview cancelled ({}); live database was never modified.", cancelled);
        Map<String, Object> result = new HashMap<>();
        result.put("cancelled", cancelled);
        return result;
    }

    public synchronized void setReviewerComment(String comment) {
        this.reviewerComment = comment;
    }

    // ---- internals ---------------------------------------------------------

    private void clearPreview() {
        DataSource retired = switchableDataSource.endPreview();
        if (retired instanceof HikariDataSource h) {
            h.close();
        } else if (previewDataSource != null) {
            previewDataSource.close();
        }
        try {
            deletePreviewFiles(Paths.get(previewDbBase));
        } catch (IOException e) {
            log.warn("Could not delete preview db files", e);
        }
        active = false;
        file = null;
        autoValidation = null;
        reviewerComment = null;
        startedAt = null;
        looksHealthy = false;
        previewDataSource = null;
    }

    private Path resolveBackup(String fileName) {
        if (fileName == null || fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            throw new IllegalArgumentException("Invalid backup file name.");
        }
        Path p = Paths.get(backupDir).resolve(fileName);
        if (!Files.isRegularFile(p) || !fileName.endsWith(".sql")) {
            throw new IllegalArgumentException("Backup not found: " + fileName);
        }
        return p;
    }

    private HikariDataSource hikari(String url) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername("sa");
        ds.setPassword("");
        ds.setMaximumPoolSize(2);
        ds.setPoolName("preview-pool");
        return ds;
    }

    private static String sqlPath(Path p) {
        return p.toAbsolutePath().toString().replace("\\", "/");
    }

    private void deletePreviewFiles(Path base) throws IOException {
        Path dir = base.getParent();
        if (dir == null || !Files.isDirectory(dir)) return;
        String name = base.getFileName().toString();
        try (var stream = Files.list(dir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (p.getFileName().toString().startsWith(name)) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    /** Row counts + mapping distribution + orphan checks for one database. */
    private Map<String, Object> summarize(DataSource ds) {
        Map<String, Object> m = new HashMap<>();
        try (Connection c = ds.getConnection()) {
            m.put("entries", scalar(c, "SELECT COUNT(*) FROM budget_entries"));
            m.put("transactions", scalar(c, "SELECT COUNT(*) FROM transactions"));
            m.put("runs", scalar(c, "SELECT COUNT(*) FROM analysis_runs"));
            m.put("cache", scalar(c, "SELECT COUNT(*) FROM merchant_categories"));
            m.put("manual", scalar(c, "SELECT COUNT(*) FROM transaction_mappings WHERE status='MAPPED_MANUAL'"));
            m.put("ai", scalar(c, "SELECT COUNT(*) FROM transaction_mappings WHERE status='MAPPED_AI'"));
            m.put("hint", scalar(c, "SELECT COUNT(*) FROM transaction_mappings WHERE status='MAPPED_HINT'"));
            m.put("parked", scalar(c, "SELECT COUNT(*) FROM transaction_mappings WHERE status='PARKED'"));
            m.put("excluded", scalar(c,
                    "SELECT COUNT(*) FROM transaction_mappings WHERE status IN ('EXCLUDED','EXCLUDED_ONCE')"));
            // Referential integrity: mappings pointing at rows that don't exist.
            m.put("orphanTxn", scalar(c,
                    "SELECT COUNT(*) FROM transaction_mappings m WHERE NOT EXISTS "
                    + "(SELECT 1 FROM transactions t WHERE t.id=m.transaction_id)"));
            m.put("orphanEntry", scalar(c,
                    "SELECT COUNT(*) FROM transaction_mappings m WHERE m.budget_entry_id IS NOT NULL AND NOT EXISTS "
                    + "(SELECT 1 FROM budget_entries e WHERE e.id=m.budget_entry_id)"));
            m.put("ok", true);
        } catch (SQLException e) {
            m.put("ok", false);
            m.put("error", e.getMessage());
        }
        return m;
    }

    /**
     * Columns the live database has that this backup's schema does not.
     *
     * <p>{@code ddl-auto=update} runs once, at startup, against whatever the datasource
     * pointed at then — so neither a preview nor a committed restore is ever brought up to
     * the current entity schema. {@code EnumColumnMigration} closes the enum-widening half
     * of that; a column added since the backup was written is the other half, and every
     * read of the affected table then fails until the app restarts. During a preview that
     * is the whole preview, which is precisely when the user is meant to be browsing to
     * validate it.
     *
     * <p>Generic on purpose: comparing the two schemas needs no list of "recent" columns to
     * keep up to date. Reported so the validation panel can say the backup is older than
     * the running app rather than declaring it healthy and letting the screens break.
     */
    private List<String> missingColumns(DataSource preview) {
        List<String> missing = new ArrayList<>();
        try (Connection previewConn = preview.getConnection();
             Connection liveConn = switchableDataSource.getLive().getConnection()) {
            Map<String, Set<String>> previewSchema = columnsByTable(previewConn);
            for (Map.Entry<String, Set<String>> table : columnsByTable(liveConn).entrySet()) {
                Set<String> here = previewSchema.getOrDefault(table.getKey(), Set.of());
                if (here.isEmpty()) {
                    continue; // whole table absent: a new table, not a missing column
                }
                for (String column : table.getValue()) {
                    if (!here.contains(column)) {
                        missing.add(table.getKey() + "." + column);
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("Could not compare the backup's schema against the live one.", e);
        }
        return missing;
    }

    private Map<String, Set<String>> columnsByTable(Connection c) throws SQLException {
        Map<String, Set<String>> byTable = new HashMap<>();
        try (ResultSet rs = c.getMetaData().getColumns(null, "PUBLIC", "%", "%")) {
            while (rs.next()) {
                byTable.computeIfAbsent(rs.getString("TABLE_NAME").toUpperCase(), k -> new HashSet<>())
                        .add(rs.getString("COLUMN_NAME").toUpperCase());
            }
        }
        return byTable;
    }

    private long scalar(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private String buildValidation(String fileName, Map<String, Object> b, Map<String, Object> live) {
        if (!Boolean.TRUE.equals(b.get("ok"))) {
            looksHealthy = false;
            return "Could not read the backup: " + b.get("error");
        }
        long orphanTxn = (long) b.get("orphanTxn");
        long orphanEntry = (long) b.get("orphanEntry");
        long mapped = (long) b.get("manual") + (long) b.get("ai") + (long) b.get("hint");
        long liveMapped = liveOk(live) ? (long) live.get("manual") + (long) live.get("ai") + (long) live.get("hint") : -1;

        List<String> issues = new ArrayList<>();
        if (orphanTxn > 0) issues.add(orphanTxn + " mapping(s) reference a missing transaction");
        if (orphanEntry > 0) issues.add(orphanEntry + " mapping(s) reference a missing budget entry");
        @SuppressWarnings("unchecked")
        List<String> missing = (List<String>) b.getOrDefault("missingColumns", List.of());
        if (!missing.isEmpty()) {
            issues.add("older than this version of the app - missing column(s) "
                    + String.join(", ", missing)
                    + ". Screens reading those tables will fail until the app is restarted");
        }
        boolean integrityOk = issues.isEmpty();

        StringBuilder sb = new StringBuilder();
        sb.append("Backup ").append(fileName).append(": ")
          .append(b.get("entries")).append(" budget entries, ")
          .append(b.get("transactions")).append(" transactions, ")
          .append(b.get("runs")).append(" run(s). Mappings - manual ").append(b.get("manual"))
          .append(", AI ").append(b.get("ai")).append(", hint ").append(b.get("hint"))
          .append(", parked ").append(b.get("parked")).append(", excluded ").append(b.get("excluded"))
          .append(". Merchant cache ").append(b.get("cache")).append(".\n");

        sb.append("Integrity: ").append(integrityOk ? "OK (no orphaned mappings)." : ("PROBLEM - " + String.join("; ", issues) + "."));

        if (liveMapped >= 0) {
            sb.append("\nVs current live: entries ").append(b.get("entries")).append(" vs ").append(live.get("entries"))
              .append(", manual ").append(b.get("manual")).append(" vs ").append(live.get("manual"))
              .append(", total mapped ").append(mapped).append(" vs ").append(liveMapped).append(".");
        }

        looksHealthy = integrityOk && (long) b.get("transactions") > 0 && (long) b.get("entries") > 0;
        sb.append("\nAssessment: ").append(
                !integrityOk ? "has integrity problems - do not commit without checking."
                : looksHealthy ? "structurally healthy and safe to commit if the data looks right as you browse."
                : "loads, but looks empty/sparse - confirm this is the state you want before committing.");
        return sb.toString();
    }

    private boolean liveOk(Map<String, Object> live) {
        return Boolean.TRUE.equals(live.get("ok"));
    }

    /**
     * A plain-English recommendation about committing this backup, focused on the
     * decision the user actually faces: is it healthy, and does committing gain or
     * lose work versus the current live data? All ASCII (compiled string).
     */
    private String buildReviewerNote(Map<String, Object> b, Map<String, Object> live) {
        if (!Boolean.TRUE.equals(b.get("ok"))) {
            return "Auto-review: this backup could not be read, so it cannot be restored. Do not commit.";
        }
        long orphans = (long) b.get("orphanTxn") + (long) b.get("orphanEntry");
        long tx = (long) b.get("transactions");
        long entries = (long) b.get("entries");
        long bMapped = (long) b.get("manual") + (long) b.get("ai") + (long) b.get("hint");
        long bManual = (long) b.get("manual");

        if (orphans > 0) {
            return "Auto-review: WARNING - this backup has " + orphans + " mapping(s) pointing at rows that "
                    + "don't exist, so it is structurally inconsistent. Do not commit without investigating.";
        }
        if (tx == 0 || entries == 0) {
            return "Auto-review: this backup looks empty (" + entries + " budget entries, " + tx
                    + " transactions). Almost certainly not the state you want - only commit if you deliberately want a blank slate.";
        }
        if (!liveOk(live)) {
            return "Auto-review: structurally sound (" + entries + " entries, " + bManual + " manual mappings, "
                    + "no integrity problems). Could not compare against the current live data; confirm the figures "
                    + "look right as you browse before committing.";
        }

        long liveMapped = (long) live.get("manual") + (long) live.get("ai") + (long) live.get("hint");
        long liveManual = (long) live.get("manual");
        long liveEntries = (long) live.get("entries");

        StringBuilder sb = new StringBuilder("Auto-review: structurally sound, no integrity problems. ");
        if (bMapped > liveMapped || bManual > liveManual || entries > liveEntries) {
            sb.append("This backup is RICHER than your current live data (").append(bManual).append(" manual vs ")
              .append(liveManual).append(", ").append(bMapped).append(" total mapped vs ").append(liveMapped)
              .append(", ").append(entries).append(" entries vs ").append(liveEntries)
              .append("). It looks like a more complete state - safe to commit if the data matches what you expect as you browse.");
        } else if (bMapped < liveMapped || bManual < liveManual) {
            sb.append("CAUTION: this backup has LESS categorization than your current live data (").append(bManual)
              .append(" manual vs ").append(liveManual).append(", ").append(bMapped).append(" total mapped vs ")
              .append(liveMapped).append("). Committing would discard the more recent work now in the live db - only "
              + "proceed if you specifically want to roll back to this older state.");
        } else {
            sb.append("It matches your current live data closely (").append(bMapped).append(" mapped, ")
              .append(entries).append(" entries) - committing would be roughly a no-op.");
        }
        return sb.toString();
    }
}
