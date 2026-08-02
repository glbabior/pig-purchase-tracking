package com.pigpurchases.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pigpurchases.model.AnalysisRunSource;
import com.pigpurchases.model.StatementImport;
import com.pigpurchases.model.StatementSource;
import com.pigpurchases.model.Transaction;
import com.pigpurchases.model.TransactionMapping;
import com.pigpurchases.parser.DepositStatementParser;
import com.pigpurchases.parser.CardStatementParser;
import com.pigpurchases.parser.ExclusionRule;
import com.pigpurchases.parser.PropertyStatementParser;
import com.pigpurchases.parser.ParsedStatement;
import com.pigpurchases.parser.ParsedTransaction;
import com.pigpurchases.parser.StatementParser;
import com.pigpurchases.repository.AnalysisRunSourceRepository;
import com.pigpurchases.repository.StatementImportRepository;
import com.pigpurchases.repository.TransactionMappingRepository;
import com.pigpurchases.repository.TransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses a statement file for a source and stores its transactions. The parser
 * is chosen from the source's parserRules; spend exclusions (also from the
 * rules) are applied uniformly here so every source behaves the same. Re-ingest
 * of the same source + statement date replaces the prior import (idempotent).
 */
@Service
public class IngestService {

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private StatementImportRepository statementImportRepository;

    // Re-ingest has to carry the prior import's mappings onto the replacement rows.
    @Autowired
    private TransactionMappingRepository mappingRepository;

    @Autowired
    private AnalysisRunSourceRepository runSourceRepository;

    @Autowired
    private MappingService mappingService;

    /**
     * Every ingest leaves a trail on the Debug screen — reconciled, unreconcilable, or
     * refused. Success is logged too, so an empty Debug screen means the ingest never ran
     * rather than that it was fine.
     */
    @Autowired
    private DebugLogService debugLog;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** A prior mapping, held by transaction *content* so it can outlive the row's id. */
    private record CarriedMapping(Long runId, TransactionMapping.Status status,
                                  Long budgetEntryId, String reason) {}

    /**
     * Identifies a transaction by what it is rather than by its id: date, signed amount,
     * type, and normalized description. Re-ingesting the same statement produces the same
     * key for the same line, which is what lets a mapping survive the replacement.
     *
     * <p>Signed, and with the type, deliberately. The absolute amount collided a charge
     * with the same-day refund that reversed it, so one inherited the other's decision —
     * the same mistake duplicate detection made before it started grouping by direction.
     */
    private static String contentKey(LocalDate date, BigDecimal amount, String type, String description) {
        return date + "|" + (amount == null ? "" : amount.toPlainString())
                + "|" + (type == null ? "" : type)
                + "|" + HintMatcher.normalize(description);
    }

    public record IngestResult(Long importId, LocalDate statementDate, String fileName,
                               int transactionCount, int excludedCount) {}

    @Transactional
    public IngestResult ingest(StatementSource source, Path file) throws IOException {
        JsonNode rules = readRules(source.getParserRules());
        StatementParser parser = parserFor(rules.path("parser").asText(""));
        List<ExclusionRule> exclusions = exclusionsFrom(rules);

        ParsedStatement statement = parser.parse(file);
        LocalDate statementDate = statement.getStatementDate();

        if (statementDate == null) {
            // Every month is grouped by a transaction's actual date, so a statement with no
            // date is unusable. Worse, the null became the idempotency key below, and Spring
            // Data turns a null derived-query argument into IS NULL — so ingesting a SECOND
            // undated statement matched the first and deleted its transactions.
            debugLog.error("ingest", "No statement date parsed from " + file.getFileName()
                    + " — refusing the import. The header line the parser looks for is missing"
                    + " or in an unexpected format.");
            throw new IllegalStateException("Could not read a statement date from "
                    + file.getFileName() + ". The file may not match the source's parser.");
        }

        reconcile(statement, file.getFileName().toString(), rules.path("parser").asText(""));

        String month = statementDate.toString().substring(0, 7);

        // Path relative to the source folder, so the exact file stays locatable.
        Path base = Path.of(source.getFolderPath()).toAbsolutePath().normalize();
        Path absFile = file.toAbsolutePath().normalize();
        String relativePath = absFile.startsWith(base)
                ? base.relativize(absFile).toString() : file.getFileName().toString();

        // Idempotent: drop any prior import for this source + statement date.
        //
        // Deleting the old transactions used to leave their mappings behind, pointing at
        // rows that no longer existed. Analysis skips a mapping whose transaction is gone,
        // so the statement silently contributed *zero* to every month; the old run became
        // invisible on the Mapping screen (which lists runs by walking imports) and so
        // could never be re-run or deleted; and EXCLUDED_ONCE — carried across a re-map by
        // transaction id, with no merchant rule to rebuild it from — was lost for good.
        // The in-app guide tells the user re-loading is safe and to do it freely.
        //
        // So carry the mappings over by content instead. A line that re-parses identically
        // keeps its categorization; one the re-parse changed or newly produced gets none,
        // and is re-mapped below so it cannot sit unmapped and invisible.
        //
        // A QUEUE per key, not a single value. A statement can print the same date,
        // description and amount twice — two identical fares, two identical parking
        // charges — and deciding differently about them is exactly what "just this one"
        // is for. Collapsing both to one carried decision copied it to both replacement
        // rows: either the deliberate exclusion spread to its twin and a real charge left
        // spend, or it was lost and the excluded charge came back. Push and poll in row
        // order so the nth old row feeds the nth new one.
        Map<String, Deque<CarriedMapping>> carried = new LinkedHashMap<>();
        List<AnalysisRunSource> consumingLinks = new ArrayList<>();
        statementImportRepository.findByStatementSourceIdAndStatementDate(source.getId(), statementDate)
                .ifPresent(prev -> {
                    for (Transaction old : transactionRepository.findByStatementImportId(prev.getId())) {
                        for (TransactionMapping m : mappingRepository.findByTransactionId(old.getId())) {
                            carried.computeIfAbsent(
                                    contentKey(old.getTransactionDate(), old.getAmount(),
                                            old.getType(), old.getDescription()),
                                    k -> new ArrayDeque<>())
                                .add(new CarriedMapping(m.getAnalysisRunId(), m.getStatus(),
                                            m.getBudgetEntryId(), m.getReason()));
                        }
                        mappingRepository.deleteByTransactionId(old.getId());
                    }
                    consumingLinks.addAll(runSourceRepository.findByStatementImportId(prev.getId()));
                    transactionRepository.deleteByStatementImportId(prev.getId());
                    statementImportRepository.delete(prev);
                });

        StatementImport imp = statementImportRepository.save(new StatementImport(
                source.getId(), statementDate, file.getFileName().toString(), relativePath,
                LocalDateTime.now(), statement.getTransactions().size()));

        int excludedCount = 0;
        boolean unmatchedRows = false;
        for (ParsedTransaction pt : statement.getTransactions()) {
            boolean excluded = pt.isExcludeFromSpend()
                    || exclusions.stream().anyMatch(r -> r.matches(pt.getDescription()));
            if (excluded) {
                excludedCount++;
            }
            Transaction txn = new Transaction(pt.getDate(), pt.getDescription(), pt.getVendor(), pt.getAmount(), month);
            txn.setStatementImportId(imp.getId());
            txn.setStatementSourceId(source.getId());
            txn.setType(pt.getType().name());
            txn.setExcludeFromSpend(excluded);
            transactionRepository.save(txn);

            Deque<CarriedMapping> queue = carried.get(contentKey(txn.getTransactionDate(),
                    txn.getAmount(), txn.getType(), txn.getDescription()));
            CarriedMapping prior = queue == null ? null : queue.poll();
            if (prior != null) {
                mappingRepository.save(new TransactionMapping(prior.runId(), txn.getId(),
                        prior.budgetEntryId(), prior.status(), prior.reason()));
            } else {
                unmatchedRows = true;
            }
        }

        // Re-point the run that consumed the old import, so it stays reachable from the
        // Mapping screen and can be re-run or deleted like any other.
        for (AnalysisRunSource link : consumingLinks) {
            link.setStatementImportId(imp.getId());
            runSourceRepository.save(link);
        }

        if (!consumingLinks.isEmpty()) {
            if (unmatchedRows) {
                // At least one row came through with no carried decision — a line the
                // re-parse changed, or one it produced for the first time (fixing a parser
                // to catch a line it used to miss is precisely why someone re-loads). Such
                // a row otherwise had NO mapping at all: analysis iterates mappings rather
                // than transactions, so that money was absent from the month, the rolling
                // average and the trend chart — while the import still counted as consumed,
                // so "Map Transactions" would not pick it up either. Recounting alone left
                // the run claiming MAPPED over rows it had never mapped.
                //
                // Safe to re-map on top of the rows just carried: doMap snapshots
                // EXCLUDED_ONCE before rebuilding, and every other manual decision is
                // rebuilt from the merchant cache. Carrying first is what gives doMap the
                // EXCLUDED_ONCE rows to find — without it they died with the old ids.
                mappingService.remapImports(List.of(imp.getId()));
            } else {
                for (AnalysisRunSource link : consumingLinks) {
                    mappingService.recount(link.getAnalysisRunId());
                }
            }
        }

        return new IngestResult(imp.getId(), statementDate, imp.getFileName(),
                statement.getTransactions().size(), excludedCount);
    }

    /**
     * Check the parse against the control totals the statement itself prints, and refuse
     * the import when they disagree.
     *
     * <p>These checks already existed, but only inside {@code *ValidationTest}, which
     * {@code assumeTrue}s itself away unless the personal PDF folder is present — so they
     * ran on one machine, when someone remembered, and never in the running app.
     * {@link ParsedStatement} has carried the totals for exactly this purpose the whole
     * time and nothing read them. A parser that dropped or mis-signed a line produced a
     * quietly wrong month and still reported "Loaded — Transactions: N".
     *
     * <p>Every outcome is written to the debug log, including success: a run that
     * reconciles says so, so silence on the Debug screen means the ingest never happened
     * rather than that it was fine.
     */
    private void reconcile(ParsedStatement statement, String fileName, String parserId) {
        BigDecimal net = statement.getTransactions().stream()
                .map(ParsedTransaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (statement.getTransactions().isEmpty()) {
            // Not fatal — a genuinely empty cycle exists — but it is nearly always a parser
            // that stopped matching, so it must not pass unremarked.
            debugLog.warn("ingest", "No transactions parsed from " + fileName
                    + " (parser " + parserId + "). If the statement is not genuinely empty,"
                    + " the parser no longer matches this layout.");
            return;
        }

        BigDecimal beginning = statement.control("beginningBalance");
        BigDecimal ending = statement.control("endingBalance");
        if (beginning != null && ending != null) {
            BigDecimal expected = ending.subtract(beginning);
            if (expected.compareTo(net) != 0) {
                fail(fileName, "signed transactions total " + net + " but the statement's own"
                        + " balances require " + expected + " (ending " + ending
                        + " minus beginning " + beginning + ")");
            }
            debugLog.info("ingest", "Reconciled " + fileName + ": " + statement.getTransactions().size()
                    + " transactions net " + net + ", matching ending minus beginning balance.");
            return;
        }

        // Checked independently: a statement with no credits in the cycle prints no credits
        // line at all, and requiring both would skip the check entirely on exactly the
        // simplest statements.
        BigDecimal purchases = statement.control("purchases");
        BigDecimal credits = statement.control("credits");
        if (purchases != null || credits != null) {
            BigDecimal parsedPurchases = sumWhere(statement, true);
            BigDecimal parsedCredits = sumWhere(statement, false);
            // Fees and interest print as ordinary dated rows, so they are inside
            // parsedPurchases — but the summary box totals them on their own lines. Compare
            // against the sum, or every cycle carrying an annual fee, a late fee or an
            // interest charge is refused for a parser problem that does not exist.
            BigDecimal expectedPositives = orZero(purchases)
                    .add(orZero(statement.control("fees")))
                    .add(orZero(statement.control("interest")));
            if (purchases != null && parsedPurchases.compareTo(expectedPositives) != 0) {
                fail(fileName, "purchases, fees and interest total " + parsedPurchases
                        + " but the statement prints " + expectedPositives
                        + " (purchases " + purchases + ", fees " + orZero(statement.control("fees"))
                        + ", interest " + orZero(statement.control("interest")) + ")");
            }
            if (credits != null && parsedCredits.compareTo(credits) != 0) {
                fail(fileName, "credits total " + parsedCredits
                        + " but the statement prints " + credits);
            }
            debugLog.info("ingest", "Reconciled " + fileName + ": " + statement.getTransactions().size()
                    + " transactions, purchases " + parsedPurchases + " and credits " + parsedCredits
                    + ", matching every total the statement prints.");
            return;
        }

        // Ridgeline prints no independent total — utilitiesTotal is derived from the very
        // rows being checked, so it cannot catch anything. Its guard is the divider check in
        // the parser instead. Say so rather than implying the parse was verified.
        debugLog.warn("ingest", "No independent control totals for " + fileName
                + " (parser " + parserId + "), so the parse could not be reconciled. "
                + statement.getTransactions().size() + " transactions totalling " + net + ".");
    }

    private void fail(String fileName, String detail) {
        String message = "Parse of " + fileName + " does not reconcile: " + detail
                + ". Refusing the import — loading it would make that month's spend wrong"
                + " with no other sign of a problem.";
        debugLog.error("ingest", message);
        throw new IllegalStateException(message);
    }

    /** A control total the statement did not print contributes nothing. */
    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** Sum of the positive (purchase) or negative (credit) amounts. */
    private static BigDecimal sumWhere(ParsedStatement statement, boolean positive) {
        return statement.getTransactions().stream()
                .map(ParsedTransaction::getAmount)
                .filter(a -> positive ? a.signum() > 0 : a.signum() < 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private StatementParser parserFor(String parserId) {
        return switch (parserId) {
            case "card-pdf" -> new CardStatementParser();
            case "deposit-checking-pdf", "deposit-business-pdf" -> new DepositStatementParser();
            case "property-rent-pdf" -> new PropertyStatementParser();
            default -> throw new IllegalArgumentException("No parser configured for id: '" + parserId + "'");
        };
    }

    private JsonNode readRules(String parserRules) {
        try {
            if (parserRules != null && !parserRules.isBlank()) {
                return objectMapper.readTree(parserRules);
            }
        } catch (Exception ignored) {
            // fall through to empty
        }
        return objectMapper.createObjectNode();
    }

    private List<ExclusionRule> exclusionsFrom(JsonNode rules) {
        List<ExclusionRule> result = new ArrayList<>();
        JsonNode arr = rules.get("excludeFromSpend");
        if (arr != null && arr.isArray()) {
            for (JsonNode node : arr) {
                result.add(new ExclusionRule(node.path("contains").asText(null), node.path("reason").asText(null)));
            }
        }
        return result;
    }
}
