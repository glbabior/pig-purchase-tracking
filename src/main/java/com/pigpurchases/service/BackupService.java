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
import java.nio.file.StandardCopyOption;
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
    /** Last backup failure, surfaced in status() so it is not console-only. */
    private volatile String lastFailure = null;

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
        // Forced, which the change-signature javadoc has always claimed and the code did not
        // do. The fingerprint has one acknowledged blind spot — a rename to the same length
        // with the same first letter — and without a forced run at least once per session,
        // such an edit is never backed up at all: the shutdown run skipped it, and so did
        // the next startup, because seedFromNewestBackup restored the matching signature.
        // Once per session is a cheap price for closing that.
        if (enabled) runBackup("shutdown", true);
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
    /**
     * Accept the current database as the new normal, then back it up.
     *
     * <p>The anti-clobber guard is right to fire on an unexplained collapse in mapped rows,
     * but it could not tell one from a deliberate one — and deleting an analysis run is a
     * supported action that halves the count in a click. From then on every backup was
     * filed {@code .SUSPECT}, the day's real file was never refreshed, pruning stopped
     * entirely, and a restart re-seeded the same high baseline from the last normal
     * sidecar. A restore commit was the only thing that cleared it.
     *
     * <p>So give the user the other half of the warning: a way to say the drop was
     * intended. Deliberately separate from {@link #backupNow()}, which still respects the
     * guard, so accepting a collapse stays an explicit act rather than something a routine
     * "Back up now" does by accident.
     */
    public synchronized Map<String, Object> acceptCurrentAsNormal() {
        lastSignature = null;
        lastGoodRichness = -1;
        lastWarning = null;
        runBackup("baseline-accepted", true);
        return status();
    }

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
        m.put("failure", lastFailure);
        m.put("files", listBackups());
        return m;
    }

    /**
     * How many daily backups to keep, read from the <b>live</b> database.
     *
     * <p>This used to go through {@code appSettingsRepository}, which is an ordinary Spring
     * Data repository on the {@code @Primary} switch — so during a restore preview it read
     * the <i>previewed backup's</i> retention count and {@code prune} then deleted real
     * backup files accordingly. Routing the dump to live was not enough; every read has to
     * go to live, and this was the one left behind. {@code Files.deleteIfExists} does not
     * come back.
     */
    private int effectiveKeep() {
        try (Connection c = liveConnection()) {
            long keep = scalar(c,
                    "SELECT COALESCE(backup_retention_count, 0) FROM app_settings WHERE id = 1");
            return keep > 0 ? (int) keep : defaultKeep;
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

            // Dump to a temp file, then move it into place. SCRIPT TO truncates its target
            // and streams into it, so writing straight to the daily file destroyed the
            // previous good copy before knowing the new one would complete — a kill during
            // the shutdown dump, a full disk, or a scanner lock left a truncated .sql that
            // RUNSCRIPT loads partway before erroring. The .meta was written only on
            // success, so the previous sidecar survived and went on vouching for the
            // wreckage, and nothing in the Settings list distinguishes the two.
            Path staging = dir.resolve(target.getFileName() + ".part");
            try (Connection c = liveConnection(); Statement st = c.createStatement()) {
                st.execute("SCRIPT TO '" + staging.toAbsolutePath().toString().replace("\\", "/") + "'");
            } catch (SQLException | RuntimeException e) {
                Files.deleteIfExists(staging);
                throw e;
            }
            // Dump first, sidecar second. The previous order moved the .meta into place
            // ahead of the .sql, which guaranteed the very thing the comment claimed to
            // prevent: if the second move failed — and on Windows ATOMIC_MOVE fails with a
            // sharing violation when an indexer or scanner holds the target open — the new
            // sidecar was left vouching for yesterday's dump, and seedFromNewestBackup
            // trusted it on the next start.
            //
            // This way round fails safe: a complete dump with a stale sidecar understates
            // richness, which costs at most one redundant backup. A sidecar without its
            // dump is a lie.
            writeMeta(staging, signature, richness, now, trigger);
            try {
                Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                Files.move(metaPath(staging), metaPath(target),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                // A crash or a failed move otherwise leaves a full dump's worth of bytes
                // behind, invisible to prune (which only walks *.sql).
                Files.deleteIfExists(metaPath(staging));
                Files.deleteIfExists(staging);
            }

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
            lastFailure = null;
            prune(dir, effectiveKeep());
            log.info("Database backup written: {} (trigger={}, mappings={})", target.getFileName(), trigger, richness);
        } catch (Exception e) {
            // Record it, don't just log it. Every failure here was previously console-only:
            // Settings went on displaying the last SUCCESSFUL timestamp with no error state,
            // and "Back up now" reported success on a run that wrote nothing. An unwritable
            // folder, a full disk, a scanner holding the daily file open, or a missing table
            // after restoring an old backup all looked identical to everything being fine —
            // in the one subsystem whose whole job is to be trustworthy.
            lastFailure = LocalDateTime.now() + " (" + trigger + "): " + e.getMessage();
            log.error("Database backup ({}) failed", trigger, e);
        }
    }

    /**
     * A cheap fingerprint of everything worth backing up. Counts catch adds/removes;
     * the mapping sums catch re-categorisations that keep counts the same; the text and
     * numeric sums catch edits in place.
     *
     * <p>This used to read {@code budget_entries} as a bare {@code COUNT(*)} and not read
     * {@code statement_sources} at all, so editing an entry's allowance or hints — or
     * renaming a source, or repointing its folder — left the fingerprint byte-identical
     * and every backup for the rest of the session was skipped as "no changes". Losing
     * the live file then restored the old allowance and none of the new hints, which
     * silently changes both budget-vs-actual and how future statements categorize.
     *
     * <p>Text columns contribute their length and first-character code rather than a real
     * hash, which is cheap and catches every realistic edit. The one edit it cannot see is
     * a rename to a string of identical length whose first letter is unchanged, with no
     * other field touched. The shutdown backup runs with {@code force}, so such an edit is
     * captured once per session even though no scheduled run notices it.
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

        // Edits in place — an allowance corrected, hints added, a source renamed or
        // repointed. None of these move a row count.
        sb.append("entryval=").append(scalar(c, "SELECT COALESCE(SUM(COALESCE(monthly_allowance,0)),0)"
                + " + COALESCE(SUM(COALESCE(quantity,0)),0) FROM budget_entries")).append(';');
        sb.append("entrytxt=").append(textFingerprint(c, "budget_entries", "name", "hints")).append(';');
        sb.append("sources=").append(scalar(c, "SELECT COUNT(*) FROM statement_sources")).append(';');
        sb.append("sourcetxt=").append(
                textFingerprint(c, "statement_sources", "name", "folder_path", "parser_rules")).append(';');
        sb.append("prefs=").append(scalar(c, "SELECT COALESCE(SUM("
                + "COALESCE(debug_log_retention_days,0) + COALESCE(notification_day_of_month,0)"
                + " + COALESCE(backup_retention_count,0)),0) FROM app_settings")).append(';');
        sb.append("months=").append(scalar(c,
                "SELECT COUNT(*) FROM month_status WHERE complete=TRUE")).append(';');
        // Dismissing duplicate groups was invisible to the fingerprint, so a session spent
        // clearing the Duplicates screen and doing nothing else was never backed up — and
        // the next startup skipped too, because the sidecar restored the same signature.
        // Losing the live db then brought every dismissed group back as unresolved.
        sb.append("dismissed=").append(scalar(c, "SELECT COUNT(*) FROM dismissed_duplicates")).append(';');
        return sb.toString();
    }

    /**
     * Length plus first-character code, summed over the given text columns. Cheap, and
     * it moves for any edit except a same-length rename with the same first letter.
     */
    private String textFingerprint(Connection c, String table, String... columns) throws SQLException {
        StringBuilder expr = new StringBuilder();
        for (String column : columns) {
            if (!expr.isEmpty()) {
                expr.append(" + ");
            }
            expr.append("LENGTH(COALESCE(").append(column).append(",''))")
                .append(" + COALESCE(ASCII(NULLIF(").append(column).append(",'')),0)");
        }
        return String.valueOf(scalar(c, "SELECT COALESCE(SUM(" + expr + "),0) FROM " + table));
    }

    /** "Real work" metric: mappings a person or the AI produced, used by the anti-clobber guard. */
    private int mappedCount(Connection c) throws SQLException {
        return (int) scalar(c,
                "SELECT COUNT(*) FROM transaction_mappings WHERE status IN ('MAPPED_HINT','MAPPED_AI','MAPPED_MANUAL')");
    }

    /**
     * One term of the fingerprint, or {@code -1} when the query cannot run.
     *
     * <p>Deliberately swallows a failure per term rather than aborting the dump. A restore
     * of a backup predating a table leaves live without it until the next startup, and
     * {@code computeSignature} reads {@code month_status} — so one missing table threw out
     * of the whole method, {@code runBackup} caught it, and <b>no backup was written again
     * for the rest of the session</b>, including the one the restore itself triggers. A
     * coarser fingerprint is a far better failure than a dead backup subsystem.
     */
    private long scalar(Connection c, String sql) {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            log.warn("Backup fingerprint term unavailable ({}): {}", sql, e.getMessage());
            return -1L;
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

    /**
     * Normal (non-suspect) daily backups, newest first by filename (date-named ⇒
     * chronological). This is what {@link #prune} deletes from and what seeds the
     * anti-clobber baseline, so two kinds of file are deliberately excluded:
     * {@code .SUSPECT} snapshots, and {@code -rollback-} snapshots taken before a restore
     * commit. Neither should ever be auto-deleted, and neither represents "normal" state.
     * They are still listed for the restore picker — see {@link #listBackups()}.
     */
    private List<Path> normalBackups(Path dir) throws IOException {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (var stream = Files.list(dir)) {
            stream.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith(PREFIX) && n.endsWith(".sql")
                        && !n.contains(".SUSPECT.") && !n.startsWith(PREFIX + "rollback-");
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
                    // Flagged so the picker can label it: this is the state that existed
                    // immediately before a restore commit, which is what you want if the
                    // commit turned out to be a mistake.
                    m.put("rollback", p.getFileName().toString().startsWith(PREFIX + "rollback-"));
                    out.add(m);
                }
            }
        } catch (IOException e) {
            log.warn("Could not list backups", e);
        }
        return out;
    }
}
