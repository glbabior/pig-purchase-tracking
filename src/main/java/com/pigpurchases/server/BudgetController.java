package com.pigpurchases.server;

import com.pigpurchases.model.AppSettings;
import com.pigpurchases.model.BudgetEntry;
import com.pigpurchases.model.StatementImport;
import com.pigpurchases.model.StatementSource;
import com.pigpurchases.model.Transaction;
import com.pigpurchases.repository.AppSettingsRepository;
import com.pigpurchases.repository.BudgetEntryRepository;
import com.pigpurchases.repository.MerchantCategoryRepository;
import com.pigpurchases.repository.StatementImportRepository;
import com.pigpurchases.repository.StatementSourceRepository;
import com.pigpurchases.repository.TransactionMappingRepository;
import com.pigpurchases.repository.TransactionRepository;
import com.pigpurchases.service.IngestService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api")
public class BudgetController {
    @Autowired
    private BudgetEntryRepository budgetEntryRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private AppSettingsRepository appSettingsRepository;

    @Autowired
    private StatementSourceRepository statementSourceRepository;

    @Autowired
    private StatementImportRepository statementImportRepository;

    // Both only for deleteEntry, which has to know what still points at an entry.
    @Autowired
    private TransactionMappingRepository mappingRepository;

    @Autowired
    private MerchantCategoryRepository merchantCategoryRepository;

    @Autowired
    private IngestService ingestService;

    @Autowired
    private ApplicationContext applicationContext;

    private static final Long SETTINGS_ID = 1L;
    private static final BigDecimal MONTHS_PER_YEAR = BigDecimal.valueOf(12);

    @GetMapping("/health")
    public Map<String, Object> health() {
        // startup changes when the context is rebuilt, so the browser can tell a
        // genuine restart from the server merely staying up during a rebuild.
        return Map.of("status", "ok", "startup", applicationContext.getStartupDate());
    }

    /**
     * Rebuilds and restarts the app so source changes are picked up without a
     * terminal. It recompiles via the Maven wrapper first, then (only on success)
     * touches the DevTools trigger file to request exactly one restart, which
     * reloads the freshly compiled classes through the restart classloader.
     *
     * <p>Going through the trigger file (rather than calling Restarter directly)
     * is deliberate: with {@code spring.devtools.restart.trigger-file} set,
     * DevTools ignores the compile's individual .class writes and restarts only
     * when this one file changes — so a single, clean restart fires on DevTools'
     * own watcher thread instead of racing a second auto-restart.
     *
     * <p>If the compile fails the app is left running the old, working code and
     * the compiler output is returned so the UI can show it.
     */
    @PostMapping("/restart")
    public ResponseEntity<Map<String, String>> restart() {
        File projectDir = new File(System.getProperty("user.dir"));
        try {
            boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
            List<String> command = windows
                    ? List.of("cmd.exe", "/c", "mvnw.cmd", "-q", "-DskipTests", "compile")
                    : List.of("./mvnw", "-q", "-DskipTests", "compile");

            Process process = new ProcessBuilder(command)
                    .directory(projectDir)
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            if (exit != 0) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                        "status", "compile-failed",
                        "log", output.isBlank() ? "Compile failed (exit " + exit + ")." : output));
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "status", "error", "log", "Rebuild error: " + e));
        }

        // Compile is fully finished, so touching the trigger now can't restart
        // mid-write. A changing timestamp guarantees DevTools sees a modification.
        try {
            Path trigger = projectDir.toPath().resolve("target").resolve("classes").resolve(".reloadtrigger");
            Files.createDirectories(trigger.getParent());
            Files.writeString(trigger, Long.toString(System.currentTimeMillis()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "status", "error", "log", "Could not request restart: " + e));
        }
        return ResponseEntity.ok(Map.of("status", "restarting"));
    }

    @GetMapping("/settings")
    public Map<String, Object> getSettings() {
        return settingsResponse(loadOrCreateSettings());
    }

    @PutMapping("/settings")
    public Map<String, Object> updateSettings(@RequestBody Map<String, Object> payload) {
        AppSettings settings = loadOrCreateSettings();
        settings.setAnnualBudget(new BigDecimal(String.valueOf(payload.get("annualBudget"))));
        if (payload.get("debugLogRetentionDays") != null) {
            int days = ((Number) payload.get("debugLogRetentionDays")).intValue();
            settings.setDebugLogRetentionDays(Math.max(0, days));
        }
        if (payload.get("notificationDayOfMonth") != null) {
            int day = ((Number) payload.get("notificationDayOfMonth")).intValue();
            settings.setNotificationDayOfMonth(Math.max(0, Math.min(31, day)));
        }
        if (payload.get("backupRetentionCount") != null) {
            int keep = ((Number) payload.get("backupRetentionCount")).intValue();
            settings.setBackupRetentionCount(Math.max(1, keep)); // always keep at least one
        }
        return settingsResponse(appSettingsRepository.save(settings));
    }

    private AppSettings loadOrCreateSettings() {
        return appSettingsRepository.findById(SETTINGS_ID).orElseGet(() -> {
            AppSettings created = new AppSettings();
            created.setId(SETTINGS_ID);
            created.setAnnualBudget(BigDecimal.ZERO);
            return appSettingsRepository.save(created);
        });
    }

    private Map<String, Object> settingsResponse(AppSettings settings) {
        BigDecimal annual = settings.getAnnualBudget() != null ? settings.getAnnualBudget() : BigDecimal.ZERO;
        BigDecimal monthly = annual.divide(MONTHS_PER_YEAR, 2, RoundingMode.HALF_UP);
        Map<String, Object> response = new HashMap<>();
        response.put("annualBudget", annual.toPlainString());
        response.put("monthlyAllowance", monthly.toPlainString());
        response.put("debugLogRetentionDays", settings.getDebugLogRetentionDays());
        response.put("notificationDayOfMonth", settings.getNotificationDayOfMonth());
        response.put("backupRetentionCount", settings.getBackupRetentionCount());
        return response;
    }

    // ---- Statement sources -------------------------------------------------
    // parserRules is stored per source but intentionally never returned to the
    // UI, and is preserved across updates (the UI only edits name + folderPath).

    @GetMapping("/statement-sources")
    public List<Map<String, Object>> getStatementSources() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (StatementSource source : statementSourceRepository.findAll()) {
            result.add(statementSourceResponse(source));
        }
        return result;
    }

    @PostMapping("/statement-sources")
    public Map<String, Object> createStatementSource(@RequestBody Map<String, Object> payload) {
        StatementSource source = new StatementSource();
        source.setName(trimOrNull(payload.get("name")));
        source.setFolderPath(trimOrNull(payload.get("folderPath")));
        return statementSourceResponse(statementSourceRepository.save(source));
    }

    @PutMapping("/statement-sources/{id}")
    public Map<String, Object> updateStatementSource(@PathVariable Long id, @RequestBody Map<String, Object> payload) {
        StatementSource source = statementSourceRepository.findById(id).orElseThrow();
        source.setName(trimOrNull(payload.get("name")));
        source.setFolderPath(trimOrNull(payload.get("folderPath")));
        // parserRules deliberately left untouched so ingest config survives edits.
        return statementSourceResponse(statementSourceRepository.save(source));
    }

    @DeleteMapping("/statement-sources/{id}")
    public void deleteStatementSource(@PathVariable Long id) {
        statementSourceRepository.deleteById(id);
    }

    // Internal endpoints for managing a source's parser rules. Not surfaced in
    // the main UI (rules are established during ingest setup, not user-edited).

    @GetMapping("/statement-sources/{id}/parser-rules")
    public Map<String, Object> getParserRules(@PathVariable Long id) {
        StatementSource source = statementSourceRepository.findById(id).orElseThrow();
        Map<String, Object> map = new HashMap<>();
        map.put("id", source.getId());
        map.put("parserRules", source.getParserRules() != null ? source.getParserRules() : "");
        return map;
    }

    @PutMapping("/statement-sources/{id}/parser-rules")
    public Map<String, Object> setParserRules(@PathVariable Long id, @RequestBody Map<String, Object> payload) {
        StatementSource source = statementSourceRepository.findById(id).orElseThrow();
        source.setParserRules(payload.get("parserRules") != null ? String.valueOf(payload.get("parserRules")) : null);
        statementSourceRepository.save(source);
        return getParserRules(id);
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ---- Ingest: in-app file browser, ingest a file, and import history -----

    @GetMapping("/statement-sources/{id}/files")
    public Map<String, Object> browseFiles(@PathVariable Long id,
                                           @RequestParam(required = false, defaultValue = "") String relPath) throws IOException {
        StatementSource source = statementSourceRepository.findById(id).orElseThrow();
        Path base = Path.of(source.getFolderPath()).toAbsolutePath().normalize();
        Path dir = resolveWithin(base, relPath);

        List<Map<String, Object>> entries = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> stream = Files.list(dir)) {
                stream.filter(p -> Files.isDirectory(p)
                                || p.getFileName().toString().toLowerCase().endsWith(".pdf"))
                        .sorted(Comparator.comparing((Path p) -> Files.isDirectory(p) ? 0 : 1)
                                .thenComparing(p -> p.getFileName().toString().toLowerCase()))
                        .forEach(p -> {
                            Map<String, Object> entry = new HashMap<>();
                            entry.put("name", p.getFileName().toString());
                            entry.put("type", Files.isDirectory(p) ? "dir" : "file");
                            entry.put("relPath", base.relativize(p).toString());
                            entries.add(entry);
                        });
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("relPath", base.relativize(dir).toString());
        response.put("atRoot", dir.equals(base));
        response.put("parentRelPath", dir.equals(base) ? null : base.relativize(dir.getParent()).toString());
        response.put("exists", Files.isDirectory(dir));
        response.put("entries", entries);
        return response;
    }

    @PostMapping("/statement-sources/{id}/ingest")
    public Map<String, Object> ingestFile(@PathVariable Long id, @RequestBody Map<String, Object> payload) throws IOException {
        StatementSource source = statementSourceRepository.findById(id).orElseThrow();
        Path base = Path.of(source.getFolderPath()).toAbsolutePath().normalize();
        Path file = resolveWithin(base, String.valueOf(payload.get("relPath")));
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Not a file: " + payload.get("relPath"));
        }
        IngestService.IngestResult result = ingestService.ingest(source, file);

        Map<String, Object> response = new HashMap<>();
        response.put("importId", result.importId());
        response.put("statementDate", result.statementDate() != null ? result.statementDate().toString() : null);
        response.put("fileName", result.fileName());
        response.put("transactionCount", result.transactionCount());
        response.put("excludedCount", result.excludedCount());
        return response;
    }

    @GetMapping("/statement-sources/{id}/imports")
    public List<Map<String, Object>> getImports(@PathVariable Long id) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (StatementImport imp : statementImportRepository.findByStatementSourceIdOrderByStatementDateDesc(id)) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", imp.getId());
            map.put("statementDate", imp.getStatementDate() != null ? imp.getStatementDate().toString() : null);
            map.put("fileName", imp.getFileName());
            map.put("relativePath", imp.getRelativePath());
            map.put("importedAt", imp.getImportedAt() != null ? imp.getImportedAt().toString() : null);
            map.put("transactionCount", imp.getTransactionCount());
            result.add(map);
        }
        return result;
    }

    /**
     * Serve the original source PDF for an import, so a transaction is traceable
     * to its file. Returned as a Resource so Spring supports HTTP range requests
     * (the browser PDF viewer needs 206/Accept-Ranges, or it renders blank).
     */
    @GetMapping("/imports/{id}/file")
    public ResponseEntity<Resource> getImportFile(@PathVariable Long id) {
        StatementImport imp = statementImportRepository.findById(id).orElseThrow();
        StatementSource source = statementSourceRepository.findById(imp.getStatementSourceId()).orElseThrow();
        Path base = Path.of(source.getFolderPath()).toAbsolutePath().normalize();
        String rel = imp.getRelativePath() != null ? imp.getRelativePath() : imp.getFileName();
        Path file = resolveWithin(base, rel);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Source file not found: " + rel);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition", "inline; filename=\"" + imp.getFileName() + "\"")
                .body(new FileSystemResource(file));
    }

    /** Open the source PDF in the machine's default PDF app (e.g. Acrobat). Local app only. */
    @PostMapping("/imports/{id}/open")
    public Map<String, Object> openImportFile(@PathVariable Long id) throws IOException {
        Path file = importFile(id);
        launch(file, false);
        return Map.of("opened", true, "file", file.toString());
    }

    /** Show the source PDF in the OS file manager with the file selected. */
    @PostMapping("/imports/{id}/reveal")
    public Map<String, Object> revealImportFile(@PathVariable Long id) throws IOException {
        Path file = importFile(id);
        launch(file, true);
        return Map.of("revealed", true, "file", file.toString());
    }

    /** The on-disk PDF an import came from, resolved safely under its source folder. */
    private Path importFile(Long importId) {
        StatementImport imp = statementImportRepository.findById(importId).orElseThrow();
        StatementSource source = statementSourceRepository.findById(imp.getStatementSourceId()).orElseThrow();
        Path base = Path.of(source.getFolderPath()).toAbsolutePath().normalize();
        String rel = imp.getRelativePath() != null ? imp.getRelativePath() : imp.getFileName();
        Path file = resolveWithin(base, rel);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Source file not found: " + rel);
        }
        return file;
    }

    /**
     * Hand the file to the desktop: {@code reveal} selects it in the file
     * manager, otherwise it opens in the default handler for its type.
     *
     * The child is fire-and-forget — its streams are discarded so no pipe
     * handles linger, and we never wait on it. On Windows the launch goes
     * through {@code cmd start}, which performs a normal ShellExecute; Acrobat
     * hands the file to an already-running instance from there. This only works
     * when the server runs in the user's own interactive desktop session (as
     * {@code launch.cmd} does) — a service or a different session cannot reach
     * the running Acrobat and errors instead.
     */
    private void launch(Path file, boolean reveal) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase();
        String path = file.toAbsolutePath().toString();
        ProcessBuilder pb;
        if (os.contains("win")) {
            // "start" treats a leading quoted token as the window title, so pass an empty one.
            pb = reveal ? new ProcessBuilder("explorer.exe", "/select," + path)
                        : new ProcessBuilder("cmd", "/c", "start", "", path);
        } else if (os.contains("mac")) {
            pb = reveal ? new ProcessBuilder("open", "-R", path) : new ProcessBuilder("open", path);
        } else {
            pb = new ProcessBuilder("xdg-open", reveal ? file.getParent().toString() : path);
        }
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        pb.start();
    }

    /** Trace a transaction back to its source: import, statement date, file, and a link to view it. */
    @GetMapping("/transactions/{id}/source")
    public Map<String, Object> getTransactionSource(@PathVariable Long id) {
        Transaction txn = transactionRepository.findById(id).orElseThrow();
        Map<String, Object> map = new HashMap<>();
        map.put("transactionId", txn.getId());
        map.put("statementSourceId", txn.getStatementSourceId());
        map.put("statementImportId", txn.getStatementImportId());
        if (txn.getStatementSourceId() != null) {
            statementSourceRepository.findById(txn.getStatementSourceId())
                    .ifPresent(s -> map.put("sourceName", s.getName()));
        }
        if (txn.getStatementImportId() != null) {
            statementImportRepository.findById(txn.getStatementImportId()).ifPresent(imp -> {
                map.put("statementDate", imp.getStatementDate() != null ? imp.getStatementDate().toString() : null);
                map.put("fileName", imp.getFileName());
                map.put("relativePath", imp.getRelativePath());
                map.put("fileUrl", "/api/imports/" + imp.getId() + "/file");
            });
        }
        return map;
    }

    @GetMapping("/transactions")
    public List<Map<String, Object>> getTransactions(@RequestParam(required = false) Long sourceId,
                                                     @RequestParam(required = false) String month) {
        List<Transaction> txns;
        if (sourceId != null) {
            txns = transactionRepository.findByStatementSourceId(sourceId);
        } else if (month != null) {
            txns = transactionRepository.findByMonth(month);
        } else {
            txns = transactionRepository.findAll();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Transaction t : txns) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", t.getId());
            map.put("date", t.getTransactionDate() != null ? t.getTransactionDate().toString() : null);
            map.put("description", t.getDescription());
            map.put("vendor", t.getVendor());
            map.put("amount", t.getAmount() != null ? t.getAmount().toPlainString() : null);
            map.put("type", t.getType());
            map.put("month", t.getMonth());
            map.put("sourceId", t.getStatementSourceId());
            map.put("excludeFromSpend", t.isExcludeFromSpend());
            result.add(map);
        }
        return result;
    }

    /** Invalid input (bad path, unknown parser, etc.) -> 400 rather than 500. */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleBadRequest(IllegalArgumentException ex) {
        return Map.of("error", ex.getMessage() != null ? ex.getMessage() : "Bad request");
    }

    /**
     * A refused operation the user can resolve -> 409, so the message reaches them instead
     * of surfacing as a 500. Covers deleting a budget entry that still has transactions
     * mapped to it, and every ingest refusal — a parse that does not tie back to the
     * statement's printed totals, a statement with no readable date, and the parser guards
     * for a missing Crestline header or Ridgeline ledger row.
     *
     * <p>The message only helps if the client reads the body: both call sites in
     * {@code index.html} do, having previously thrown on the status alone and replaced
     * every one of these with "is the server running?".
     */
    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handleConflict(IllegalStateException ex) {
        return Map.of("error", ex.getMessage() != null ? ex.getMessage() : "Conflict");
    }

    /** Resolve relPath under base, rejecting anything that escapes the source folder. */
    private Path resolveWithin(Path base, String relPath) {
        String rel = (relPath == null || relPath.equals("null")) ? "" : relPath;
        Path target = base.resolve(rel).toAbsolutePath().normalize();
        if (!target.startsWith(base)) {
            throw new IllegalArgumentException("Path escapes the source folder");
        }
        return target;
    }

    private Map<String, Object> statementSourceResponse(StatementSource source) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", source.getId());
        map.put("name", source.getName() != null ? source.getName() : "");
        map.put("folderPath", source.getFolderPath() != null ? source.getFolderPath() : "");
        map.put("exclusions", extractExclusions(source.getParserRules()));
        return map;
    }

    /**
     * The user-meaningful part of a source's (otherwise hidden) parser rules:
     * the excludeFromSpend carve-outs, surfaced so they can be seen in the UI.
     */
    private List<Map<String, String>> extractExclusions(String parserRules) {
        List<Map<String, String>> result = new ArrayList<>();
        if (parserRules == null || parserRules.isBlank()) {
            return result;
        }
        try {
            JsonNode arr = objectMapper.readTree(parserRules).get("excludeFromSpend");
            if (arr != null && arr.isArray()) {
                for (JsonNode node : arr) {
                    Map<String, String> exclusion = new HashMap<>();
                    exclusion.put("contains", node.path("contains").asText(""));
                    exclusion.put("reason", node.path("reason").asText(""));
                    result.add(exclusion);
                }
            }
        } catch (Exception ignored) {
            // Malformed rules: just show no exclusions rather than failing the list.
        }
        return result;
    }

    private String trimOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    @GetMapping("/entries")
    public List<Map<String, Object>> getEntries() {
        List<BudgetEntry> entries = budgetEntryRepository.findAll();
        List<Map<String, Object>> result = new ArrayList<>();
        for (BudgetEntry entry : entries) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", entry.getId());
            map.put("title", entry.getName());
            map.put("quantity", entry.getQuantity() != null ? entry.getQuantity() : 1);
            map.put("monthlyBudget", entry.getMonthlyAllowance().toPlainString());
            map.put("hints", entry.getHints() != null ? entry.getHints() : "");
            result.add(map);
        }
        return result;
    }

    @PostMapping("/entries")
    public BudgetEntry createEntry(@RequestBody Map<String, Object> payload) {
        BudgetEntry entry = new BudgetEntry();
        entry.setName((String) payload.get("title"));
        entry.setMonthlyAllowance(new BigDecimal(String.valueOf(payload.get("monthlyBudget"))));
        entry.setQuantity(((Number) payload.getOrDefault("quantity", 1)).intValue());
        entry.setHints((String) payload.getOrDefault("hints", ""));
        return budgetEntryRepository.save(entry);
    }

    @PutMapping("/entries/{id}")
    public BudgetEntry updateEntry(@PathVariable Long id, @RequestBody Map<String, Object> payload) {
        BudgetEntry entry = budgetEntryRepository.findById(id).orElseThrow();
        entry.setName((String) payload.get("title"));
        entry.setMonthlyAllowance(new BigDecimal(String.valueOf(payload.get("monthlyBudget"))));
        entry.setQuantity(((Number) payload.getOrDefault("quantity", 1)).intValue());
        entry.setHints((String) payload.getOrDefault("hints", ""));
        return budgetEntryRepository.save(entry);
    }

    /**
     * Delete a budget entry, but refuse while transactions are still mapped to it.
     *
     * <p>A bare delete left those mappings carrying a dangling {@code budgetEntryId} and a
     * {@code MAPPED_*} status. Analysis buckets spend by entry id and then builds its rows
     * from the <i>surviving</i> entries, so the orphaned key was never read: that money left
     * every month total, the rolling average and the trend chart at once — in no category,
     * not in "Other", not in excluded — and no drill-down could reach it. Nothing repaired it
     * later, and the review screen rendered those rows as "Other (parked)" while the Other
     * tile excluded their amounts.
     *
     * <p>So make the user re-map first. The alternative — silently re-parking the affected
     * transactions here — moves someone's categorization work without asking, and this way
     * the count tells them exactly how much is at stake.
     */
    @DeleteMapping("/entries/{id}")
    public void deleteEntry(@PathVariable Long id) {
        long mapped = mappingRepository.countByBudgetEntryId(id);
        if (mapped > 0) {
            throw new IllegalStateException(mapped + " transaction" + (mapped == 1 ? " is" : "s are")
                    + " still mapped to this category. Reassign them first — open Spend: Monthly,"
                    + " click the category, and move them to another one.");
        }
        merchantCategoryRepository.findByBudgetEntryId(id).forEach(merchantCategoryRepository::delete);
        budgetEntryRepository.deleteById(id);
    }

    /**
     * Append a matching hint to an entry (stored as a "match: <text>" line), so any
     * future transaction whose description contains that text maps here automatically.
     * De-duplicates case-insensitively. Turns a one-off manual categorization into a rule.
     */
    @PostMapping("/entries/{id}/hints")
    public Map<String, Object> addHint(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String hint = body.get("hint") == null ? "" : String.valueOf(body.get("hint")).trim();
        if (hint.isEmpty()) {
            throw new IllegalArgumentException("Hint text is required.");
        }
        BudgetEntry entry = budgetEntryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No such budget entry: " + id));
        String existing = entry.getHints() != null ? entry.getHints() : "";
        boolean present = false;
        for (String line : existing.split("\\R")) {
            String t = line.trim();
            if (t.toLowerCase().startsWith("match:")
                    && t.substring("match:".length()).trim().equalsIgnoreCase(hint)) {
                present = true;
                break;
            }
        }
        if (!present) {
            String joined = existing.isBlank() ? "" : existing.stripTrailing() + "\n";
            entry.setHints(joined + "match: " + hint);
            budgetEntryRepository.save(entry);
        }
        Map<String, Object> response = new HashMap<>();
        response.put("id", id);
        response.put("added", !present);
        response.put("hints", entry.getHints());
        return response;
    }

}

