package com.pigpurchases.service;

import com.pigpurchases.config.SwitchableDataSource;
import com.pigpurchases.repository.AppSettingsRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Automatic, versioned database backups so a corrupted or reverted live database
 * can always be recovered.
 *
 * <p><b>What it writes.</b> A consistent SQL dump (H2 {@code SCRIPT TO}) — portable,
 * human-readable, and independent of the live {@code .mv.db} file, so a bad live db
 * can never corrupt the history. One file per calendar day, named for the date
 * ({@code pigpurchases-YYYY-MM-DD.sql}); a later backup on the same day refreshes
 * that day's file.
 *
 * <p><b>Where.</b> {@code pigpurchases.backup.dir} (default {@code
 * ${user.home}/pigpurchases-backups}) — deliberately OUTSIDE OneDrive and outside
 * the live-db folder, so whatever reverts/corrupts the live db can't reach the
 * backups.
 *
 * <p><b>When.</b> On startup, every {@code interval-ms} while running, on graceful
 * shutdown, and on demand — but each run first computes a cheap change signature
 * and skips writing when nothing has changed since the last backup.
 *
 * <p><b>Anti-clobber guard.</b> If a snapshot shows a large drop in real mappings
 * versus the last good backup (exactly the "reverted to an empty state" failure),
 * it is written to a {@code .SUSPECT.sql} file and a warning is raised instead of
 * overwriting the day's good backup.
 *
 * <p><b>Retention.</b> Keeps the newest N daily files (N = Settings
 * "backup retention"); older ones are pruned. {@code .SUSPECT} files are never
 * auto-pruned.
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");
    private static final String PREFIX = "pigpurchases-";

    /**
     * Deliberately the switch itself rather than the {@code @Primary} {@link DataSource},
     * so every read below can go to {@link SwitchableDataSource#getLive()}.
     *
     * <p>Injecting the plain DataSource routed backups through the switch, which means a
     * scheduled or shutdown backup taken while a restore preview was open dumped the
     * <i>preview</i> database over the day's real backup — and, when the preview was richer
     * than live, poisoned {@link #lastGoodRichness} so every later backup tripped the
     * anti-clobber guard and was filed {@code .SUSPECT} indefinitely, surviving restarts via
     * the sidecar. Reading live keeps real backups running normally throughout a preview,
     * which is better than suppressing them for its duration.
     */
    private final SwitchableDataSource dataSource;
    private final AppSettingsRepository appSettingsRepository;

    @Value("${pigpurchases.backup.enabled:true}")
    private boolean enabled;
    @Value("${pigpurchases.backup.dir:${user.home}/pigpurchases-backups}")
    private String backupDir;
    @Value("${pigpurchases.backup.default-keep:30}")
    private int defaultKeep;

    // In-memory state, seeded on startup from the newest existing backup's sidecar.
    private volatile String lastSignature = null;
    private volatile int lastGoodRichness = -1;
    private volatile LocalDateTime lastBackupAt = null;
    private volatile String lastBackupFile = null;
    private volatile String lastWarning = null;

    public BackupService(SwitchableDataSource dataSource, AppSettingsRepository appSettingsRepository) {
        this.dataSource = dataSource;
        this.appSettingsRepository = appSettingsRepository;
    }

    /**
     * A connection to the live database, never the restore preview. Every backup read
     * goes through here — see the field comment for why.
     */
    private Connection liveConnection() throws SQLException {
        return dataSource.getLive().getConnection();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!enabled) {
            log.info("Database backups are disabled (pigpurchases.backup.enabled=false).");
            return;
        }
        seedFromNewestBackup();
        runBackup("startup", false);
    }

    @Scheduled(fixedDelayString = "${pigpurchases.backup.interval-ms:600000}",
            initialDelayString = "${pigpurchases.backup.interval-ms:600000}")
    public void scheduled() {
        if (enabled) runBackup("scheduled", false);
    }

    @PreDestroy
    public void onShutdown() {
        if (enabled) runBackup("shutdown", false);
    }

    /** Manual "Back up now" — forces a write even if nothing changed. */
    public synchronized Map<String, Object> backupNow() {
        runBackup("manual", true);
        return status();
    }

    /**
     * After a restore commit the live data has intentionally changed (often to an
     * older, smaller state). Clear the change signature and the anti-clobber
     * baseline so the next backup records the restored state normally instead of
     * flagging it as a suspicious drop.
     */
    public synchronized void resetBaselineAfterRestore() {
        lastSignature = null;
        lastGoodRichness = -1;
        runBackup("post-restore", true);
    }

    public synchronized Map<String, Object> status() {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("enabled", enabled);
        m.put("dir", Paths.get(backupDir).toAbsolutePath().toString());
        m.put("retention", effectiveKeep());
        m.put("lastBackupAt", lastBackupAt == null ? null : lastBackupAt.toString());
        m.put("lastBackupFile", lastBackupFile);
        m.put("warning", lastWarning);
        m.put("files", listBackups());
        return m;
    }

    private int effectiveKeep() {
        try {
            return appSettingsRepository.findById(1L)
                    .map(s -> Math.max(1, s.getBackupRetentionCount()))
                    .orElse(defaultKeep);
        } catch (Exception e) {
            return defaultKeep;
        }
    }

    private synchronized void runBackup(String trigger, boolean force) {
        try {
            Path dir = Paths.get(backupDir);
            Files.createDirectories(dir);

            String signature;
            int richness;
            try (Connection c = liveConnection()) {
                signature = computeSignature(c);
                richness = mappedCount(c);
            }

            if (!force && signature.equals(lastSignature)) {
                log.debug("Backup ({}) skipped — no changes since last backup.", trigger);
                return;
            }

            // Anti-clobber: refuse to overwrite a good daily file with what looks
            // like a reverted/emptied database.
            boolean suspect = lastGoodRichness >= 20 && richness < lastGoodRichness / 2;
            LocalDateTime now = LocalDateTime.now();
            Path target = suspect
                    ? dir.resolve(PREFIX + STAMP.format(now) + ".SUSPECT.sql")
                    : dir.resolve(PREFIX + DAY.format(now) + ".sql");

            try (Connection c = liveConnection(); Statement st = c.createStatement()) {
                st.execute("SCRIPT TO '" + target.toAbsolutePath().toString().replace("\\", "/") + "'");
            }
            writeMeta(target, signature, richness, now, trigger);

            lastSignature = signature;
            lastBackupAt = now;
            lastBackupFile = target.getFileName().toString();

            if (suspect) {
                lastWarning = "Backup on " + now + " (" + trigger + ") showed mappings dropping from "
                        + lastGoodRichness + " to " + richness + " — possible data loss. Saved as "
                        + target.getFileName() + " WITHOUT overwriting the good daily backup. Investigate before trusting the live database.";
                log.warn(lastWarning);
                // Do not update lastGoodRichness or prune on a suspect snapshot.
                return;
            }

            lastGoodRichness = richness;
            lastWarning = null;
            prune(dir, effectiveKeep());
            log.info("Database backup written: {} (trigger={}, mappings={})", target.getFileName(), trigger, richness);
        } catch (Exception e) {
            log.error("Database backup ({}) failed", trigger, e);
        }
    }

    /**
     * A cheap fingerprint of everything worth backing up. Counts catch adds/removes;
     * the mapping sums catch re-categorisations that keep counts the same.
     */
    private String computeSignature(Connection c) throws SQLException {
        StringBuilder sb = new StringBuilder();
        for (String t : List.of("transactions", "transaction_mappings", "budget_entries",
                "analysis_runs", "merchant_categories", "app_settings")) {
            sb.append(t).append('=').append(scalar(c, "SELECT COUNT(*) FROM " + t)).append(';');
        }
        sb.append("mapsum=").append(scalar(c,
                "SELECT COALESCE(SUM(transaction_id),0)+COALESCE(SUM(budget_entry_id),0) FROM transaction_mappings")).append(';');
        sb.append("mapped=").append(mappedCount(c)).append(';');
        sb.append("parked=").append(scalar(c, "SELECT COUNT(*) FROM transaction_mappings WHERE status='PARKED'")).append(';');
        sb.append("excluded=").append(scalar(c,
                "SELECT COUNT(*) FROM transaction_mappings WHERE status IN ('EXCLUDED','EXCLUDED_ONCE')")).append(';');
        sb.append("exflag=").append(scalar(c, "SELECT COUNT(*) FROM transactions WHERE exclude_from_spend=TRUE")).append(';');
        sb.append("budget=").append(scalar(c, "SELECT COALESCE(SUM(annual_budget),0) FROM app_settings")).append(';');
        return sb.toString();
    }

    /** "Real work" metric: mappings a person or the AI produced, used by the anti-clobber guard. */
    private int mappedCount(Connection c) throws SQLException {
        return (int) scalar(c,
                "SELECT COUNT(*) FROM transaction_mappings WHERE status IN ('MAPPED_HINT','MAPPED_AI','MAPPED_MANUAL')");
    }

    private long scalar(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private void writeMeta(Path sqlFile, String signature, int richness, LocalDateTime at, String trigger) {
        Properties p = new Properties();
        p.setProperty("signature", signature);
        p.setProperty("richness", Integer.toString(richness));
        p.setProperty("at", at.toString());
        p.setProperty("trigger", trigger);
        try {
            Files.writeString(metaPath(sqlFile), toMetaString(p));
        } catch (IOException e) {
            log.warn("Could not write backup metadata for {}", sqlFile.getFileName(), e);
        }
    }

    private static String toMetaString(Properties p) {
        StringBuilder sb = new StringBuilder();
        p.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        return sb.toString();
    }

    private Path metaPath(Path sqlFile) {
        return sqlFile.resolveSibling(sqlFile.getFileName().toString() + ".meta");
    }

    /** Restore the anti-clobber baseline across restarts from the newest good backup's sidecar. */
    private void seedFromNewestBackup() {
        try {
            List<Path> files = normalBackups(Paths.get(backupDir));
            if (files.isEmpty()) return;
            Path newest = files.get(0); // sorted newest-first
            Path meta = metaPath(newest);
            if (Files.exists(meta)) {
                Properties p = new Properties();
                for (String line : Files.readAllLines(meta)) {
                    int eq = line.indexOf('=');
                    if (eq > 0) p.setProperty(line.substring(0, eq), line.substring(eq + 1));
                }
                String r = p.getProperty("richness");
                if (r != null) lastGoodRichness = Integer.parseInt(r.trim());
                lastSignature = p.getProperty("signature");
                lastBackupFile = newest.getFileName().toString();
                log.info("Seeded backup baseline from {} (mappings={}).", newest.getFileName(), lastGoodRichness);
            }
        } catch (Exception e) {
            log.warn("Could not seed backup baseline from existing backups", e);
        }
    }

    private void prune(Path dir, int keep) {
        try {
            List<Path> files = normalBackups(dir); // newest-first
            for (int i = keep; i < files.size(); i++) {
                Path f = files.get(i);
                Files.deleteIfExists(f);
                Files.deleteIfExists(metaPath(f));
                log.info("Pruned old backup {}", f.getFileName());
            }
        } catch (IOException e) {
            log.warn("Backup pruning failed", e);
        }
    }

    /** Normal (non-suspect) daily backups, newest first by filename (date-named ⇒ chronological). */
    private List<Path> normalBackups(Path dir) throws IOException {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (var stream = Files.list(dir)) {
            stream.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith(PREFIX) && n.endsWith(".sql") && !n.contains(".SUSPECT.");
            }).forEach(out::add);
        }
        out.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
        return out;
    }

    private List<Map<String, Object>> listBackups() {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            Path dir = Paths.get(backupDir);
            if (!Files.isDirectory(dir)) return out;
            try (var stream = Files.list(dir)) {
                List<Path> all = new ArrayList<>();
                stream.filter(p -> {
                    String n = p.getFileName().toString();
                    return n.startsWith(PREFIX) && n.endsWith(".sql");
                }).forEach(all::add);
                all.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
                for (Path p : all) {
                    Map<String, Object> m = new java.util.HashMap<>();
                    m.put("name", p.getFileName().toString());
                    m.put("bytes", Files.size(p));
                    m.put("suspect", p.getFileName().toString().contains(".SUSPECT."));
                    out.add(m);
                }
            }
        } catch (IOException e) {
            log.warn("Could not list backups", e);
        }
        return out;
    }
}
