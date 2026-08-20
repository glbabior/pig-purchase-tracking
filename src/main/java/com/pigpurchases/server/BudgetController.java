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
import com.pigpurchases.model.Transaction;
import com.pigpurchases.model.TransactionMapping;
import com.pigpurchases.service.AnalysisService;
import com.pigpurchases.service.IngestService;
import org.springframework.transaction.annotation.Transactional;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
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
    private com.pigpurchases.service.BudgetHistoryService budgetHistoryService;

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
    private com.pigpurchases.service.MappingService mappingService;

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

    /**
     * {@code annualBudgetChange} mirrors the entry edit's {@code budgetChange}:
     * {@code "forward"} (default) records the old annual budget as history so past
     * months keep their era-correct "Other" budget line; {@code "correct"} amends in
     * place. The annual budget is only ever versioned here, explicitly — never as a
     * side effect of a category change.
     */
    @PutMapping("/settings")
    @Transactional
    public Map<String, Object> updateSettings(@RequestBody Map<String, Object> payload) {
        AppSettings settings = loadOrCreateSettings();
        BigDecimal newAnnual = new BigDecimal(String.valueOf(payload.get("annualBudget")));
        if ("correct".equals(payload.get("annualBudgetChange"))) {
            budgetHistoryService.correctAnnualBudget(settings, newAnnual);
        } else {
            String effective = payload.get("effectiveMonth") != null
                    ? String.valueOf(payload.get("effectiveMonth")) : budgetHistoryService.currentMonth();
            budgetHistoryService.changeAnnualBudgetForward(settings, newAnnual, effective);
        }
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
        // The annual budget in force THIS month, same rule as the entries list: a
        // future-dated change shows nowhere until its month arrives.
        BigDecimal annual = budgetHistoryService.resolver().annual(budgetHistoryService.currentMonth());
        BigDecimal monthly = annual.divide(MONTHS_PER_YEAR, 2, RoundingMode.HALF_UP);
        Map<String, Object> response = new HashMap<>();
        response.put("annualBudget", annual.toPlainString());
        response.put("monthlyAllowance", monthly.toPlainString());
        response.put("debugLogRetentionDays", settings.getDebugLogRetentionDays());
        response.put("notificationDayOfMonth", settings.getNotificationDayOfMonth());
        response.put("backupRetentionCount", settings.getBackupRetentionCount());
        // The annual budget's eras (empty = never changed), so Settings can show
        // what past months are measured against without a second fetch.
        List<Map<String, Object>> history = new ArrayList<>();
        for (var era : budgetHistoryService.annualEras()) {
            Map<String, Object> eraMap = new HashMap<>();
            eraMap.put("startMonth", era.getStartMonth());
            eraMap.put("amount", era.getAmount().toPlainString());
            history.add(eraMap);
        }
        response.put("annualBudgetHistory", history);
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
        List<StatementImport> imports = statementImportRepository.findByStatementSourceIdOrderByStatementDateDesc(id);

        // The span of dates each statement actually covers — computed from its rows, not
        // stored on the import, because it is derivable and would be one more field for
        // re-ingest to keep honest. One grouped query for all imports of the source.
        Map<Long, LocalDate[]> dateRanges = new HashMap<>();
        if (!imports.isEmpty()) {
            List<Long> importIds = imports.stream().map(StatementImport::getId).toList();
            for (Object[] row : transactionRepository.transactionDateRangesByImportIds(importIds)) {
                dateRanges.put((Long) row[0], new LocalDate[] {(LocalDate) row[1], (LocalDate) row[2]});
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (StatementImport imp : imports) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", imp.getId());
            map.put("statementDate", imp.getStatementDate() != null ? imp.getStatementDate().toString() : null);
            map.put("fileName", imp.getFileName());
            map.put("relativePath", imp.getRelativePath());
            map.put("importedAt", imp.getImportedAt() != null ? imp.getImportedAt().toString() : null);
            map.put("transactionCount", imp.getTransactionCount());
            LocalDate[] range = dateRanges.get(imp.getId());
            map.put("firstTransactionDate", range != null && range[0] != null ? range[0].toString() : null);
            map.put("lastTransactionDate", range != null && range[1] != null ? range[1].toString() : null);
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

    /**
     * Show a statement source's folder in the OS file manager.
     *
     * <p>Deliberately <b>not</b> routed through {@link #launch}. That method's Windows
     * open branch goes through {@code cmd /c start} and therefore needs
     * {@link #refuseShellMetacharacters}; a folder needs no shell at all, so this takes the
     * {@code explorer.exe} route where the path is a plain argument. There is nothing for a
     * metacharacter to break out of, which means a source living under a folder like
     * {@code D:\Docs\Bank & Trust\} opens fine here while its statement files still cannot be
     * launched — the guard is on the shell branch, not on the path.
     *
     * <p>POST rather than GET because it starts a process on this machine. That is exactly
     * the kind of side effect {@code LocalOriginFilter} exists to stop another page causing,
     * and the filter only inspects state-changing methods.
     */
    @PostMapping("/statement-sources/{id}/open")
    public Map<String, Object> openSourceFolder(@PathVariable Long id) throws IOException {
        StatementSource source = statementSourceRepository.findById(id).orElseThrow();
        Path dir = Path.of(source.getFolderPath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            // The usual cause is a folder that moved or a drive that is not mounted, so say
            // which one rather than failing silently after the click does nothing.
            throw new IllegalArgumentException("This source's folder was not found: " + dir);
        }
        launchFolder(dir);
        return Map.of("opened", true, "folder", dir.toString());
    }

    /** Hand a directory to the desktop file manager. No shell on any platform. */
    private void launchFolder(Path dir) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase();
        String path = dir.toAbsolutePath().toString();
        ProcessBuilder pb;
        if (os.contains("win")) {
            pb = new ProcessBuilder("explorer.exe", path);
        } else if (os.contains("mac")) {
            pb = new ProcessBuilder("open", path);
        } else {
            pb = new ProcessBuilder("xdg-open", path);
        }
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        pb.start();
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
     * Characters that mean something to {@code cmd.exe} rather than naming a file.
     *
     * <p>{@code &} and {@code |} separate statements, {@code ^} escapes, {@code %} expands a
     * variable, {@code !} expands one again under delayed expansion, and {@code "} ends a
     * quoted argument. Windows already forbids most of these in a file name — {@code &},
     * {@code ^}, {@code %} and {@code !} it does not.
     */
    private static final java.util.regex.Pattern SHELL_METACHARACTERS =
            java.util.regex.Pattern.compile("[&|^%!\"`\\r\\n]");

    /**
     * Refuse to launch a path that could be read as a command rather than a file name.
     *
     * <p>The Windows branch below goes through {@code cmd /c start}, and the JDK quotes an
     * argument only when it contains a space or a tab. A path with no space is therefore
     * passed to {@code cmd} unquoted, and {@code cmd} reads an {@code &} in it as "end of
     * this command, start the next one". The file name comes from whatever writes into the
     * statement folder, so it is not ours to trust.
     *
     * <p>Refusing is deliberately preferred to quoting. Getting {@code cmd} quoting right for
     * every case is famously difficult, a mistake fails open, and no real statement file needs
     * these characters — so the safe answer costs nothing and the risky one has to be perfect.
     *
     * <p>Called ONLY from the Windows {@code cmd} branch. It first ran on every platform, on
     * the theory that a rule applied only where the bug is remembered is a rule that stops
     * applying — but {@code open} and {@code xdg-open} are handed their argument directly with
     * no shell in between, so there it refused files it had no reason to. It also runs against
     * the whole absolute path, which includes the user's own folders: a statement source under
     * {@code D:\Docs\Bank & Trust\} would otherwise have every file in it permanently
     * unopenable. That is still refused on Windows, because {@code cmd} really would split the
     * command there — but the message has to name the character, since the offending part of
     * the path may be a folder the user chose rather than the file they clicked.
     */
    private static void refuseShellMetacharacters(String path) {
        java.util.regex.Matcher m = SHELL_METACHARACTERS.matcher(path);
        if (m.find()) {
            throw new IllegalArgumentException(
                    "This file cannot be opened from the app. Its path contains "
                    + describe(m.group()) + ", which the Windows command shell would treat as "
                    + "an instruction rather than part of a name. Rename the file or the folder,"
                    + " then try again.\n\n" + path);
        }
    }

    /** Name the character, since several of these are invisible or easy to miss in a path. */
    private static String describe(String ch) {
        return switch (ch) {
            case "\r", "\n" -> "a line break";
            case "`" -> "a backtick (`)";
            case "\"" -> "a double quote (\")";
            default -> "'" + ch + "'";
        };
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
            // Only here. This is the one branch that hands the path to a shell — explorer.exe
            // below and the open/xdg-open branches all receive it as a plain argument.
            if (!reveal) {
                refuseShellMetacharacters(path);
            }
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

    /**
     * {@code monthlyBudget} is the amount in force <b>this month</b>, not the stored
     * (latest-era) value — with a future-dated change recorded, the list must keep
     * showing today's budget until the effective month arrives, then switch on its
     * own. One rule everywhere: screens show the era in force for the month shown.
     */
    @GetMapping("/entries")
    public List<Map<String, Object>> getEntries() {
        List<BudgetEntry> entries = budgetEntryRepository.findAll();
        var resolver = budgetHistoryService.resolver();
        String now = budgetHistoryService.currentMonth();
        List<Map<String, Object>> result = new ArrayList<>();
        for (BudgetEntry entry : entries) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", entry.getId());
            map.put("title", entry.getName());
            map.put("quantity", entry.getQuantity() != null ? entry.getQuantity() : 1);
            map.put("monthlyBudget", resolver.amount(entry, now).toPlainString());
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

    /**
     * Edit an entry. When the amount changed, {@code budgetChange} says which of two
     * different facts the user is stating — see {@link BudgetHistoryService}:
     * {@code "forward"} (the default) records the old amount as history and starts a
     * new era this month, so past months keep the budget they were lived under;
     * {@code "correct"} amends the value in force now, creating no history.
     * An unchanged amount records nothing either way.
     */
    @PutMapping("/entries/{id}")
    @Transactional
    public BudgetEntry updateEntry(@PathVariable Long id, @RequestBody Map<String, Object> payload) {
        BudgetEntry entry = budgetEntryRepository.findById(id).orElseThrow();
        entry.setName((String) payload.get("title"));
        BigDecimal newAmount = new BigDecimal(String.valueOf(payload.get("monthlyBudget")));
        if ("correct".equals(payload.get("budgetChange"))) {
            budgetHistoryService.correctEntryAmount(entry, newAmount);
        } else {
            // Optional effectiveMonth ("the pass renews in September"); absent = this month.
            String effective = payload.get("effectiveMonth") != null
                    ? String.valueOf(payload.get("effectiveMonth")) : budgetHistoryService.currentMonth();
            budgetHistoryService.changeEntryAmountForward(entry, newAmount, effective);
        }
        entry.setQuantity(((Number) payload.getOrDefault("quantity", 1)).intValue());
        entry.setHints((String) payload.getOrDefault("hints", ""));
        return budgetEntryRepository.save(entry);
    }

    /**
     * An entry's budget history: its eras oldest-first, empty when the amount has
     * never been changed "going forward" (the common case — no rows means the
     * current amount has always applied).
     */
    @GetMapping("/entries/{id}/budget-history")
    public List<Map<String, Object>> getBudgetHistory(@PathVariable Long id) {
        budgetEntryRepository.findById(id).orElseThrow();
        List<Map<String, Object>> result = new ArrayList<>();
        for (var era : budgetHistoryService.erasFor(id)) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", era.getId());
            map.put("startMonth", era.getStartMonth());
            map.put("amount", era.getAmount().toPlainString());
            result.add(map);
        }
        return result;
    }

    /** Fix one era's amount in place — "the budget for those months was actually X". */
    @PutMapping("/entries/{id}/budget-eras/{eraId}")
    @Transactional
    public void amendBudgetEra(@PathVariable Long id, @PathVariable Long eraId,
                               @RequestBody Map<String, Object> payload) {
        BudgetEntry entry = budgetEntryRepository.findById(id).orElseThrow();
        budgetHistoryService.amendEra(entry, eraId, new BigDecimal(String.valueOf(payload.get("amount"))));
        budgetEntryRepository.save(entry);
    }

    /** Remove an era boundary, merging its months into the era before it. */
    @DeleteMapping("/entries/{id}/budget-eras/{eraId}")
    @Transactional
    public void deleteBudgetEra(@PathVariable Long id, @PathVariable Long eraId) {
        BudgetEntry entry = budgetEntryRepository.findById(id).orElseThrow();
        budgetHistoryService.deleteEra(entry, eraId);
        budgetEntryRepository.save(entry);
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
     *
     * <p>It only blocks on rows the category actually <b>shows</b>. Counting every mapping
     * pointing at the entry included money-in rows — a card payment the AI promoted to a
     * category, say — which analysis routes to the excluded bucket before the entry-id test,
     * so they never appear in the drill-down the error message tells the user to open. The
     * count said three, the screen showed one, and there was no way to reach the rest.
     * Those rows carry no spend by definition, so re-parking them moves no number and is
     * done here rather than demanded of the user.
     */
    @DeleteMapping("/entries/{id}")
    @Transactional
    public void deleteEntry(@PathVariable Long id) {
        List<TransactionMapping> invisible = new ArrayList<>();
        long visible = 0;
        for (TransactionMapping m : mappingRepository.findByBudgetEntryId(id)) {
            Transaction txn = transactionRepository.findById(m.getTransactionId()).orElse(null);
            if (txn != null && AnalysisService.inSpendBuckets(m, txn)) {
                visible++;
            } else {
                invisible.add(m);
            }
        }
        if (visible > 0) {
            throw new IllegalStateException(visible + " transaction" + (visible == 1 ? " is" : "s are")
                    + " still mapped to this category. Reassign them first — open Spend: Monthly,"
                    + " click the category, and move them to another one.");
        }
        Set<Long> touchedRuns = new HashSet<>();
        for (TransactionMapping m : invisible) {
            m.setBudgetEntryId(null);
            m.setStatus(TransactionMapping.Status.PARKED);
            m.setReason("Category deleted");
            mappingRepository.save(m);
            touchedRuns.add(m.getAnalysisRunId());
        }
        // The runs' mapped/parked counts are denormalized and served straight to the
        // Mapping screen, so re-parking without recounting left those statements reporting
        // rows as mapped that no longer were, until something else happened to re-map them.
        touchedRuns.forEach(mappingService::recount);
        merchantCategoryRepository.findByBudgetEntryId(id).forEach(merchantCategoryRepository::delete);
        budgetHistoryService.deleteErasFor(id);
        budgetEntryRepository.deleteById(id);
    }

    /**
     * Append a matching hint to an entry (stored as a {@code match: <text>} line), so any
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

