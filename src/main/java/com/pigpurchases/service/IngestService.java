package com.pigpurchases.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pigpurchases.model.AnalysisRunSource;
import com.pigpurchases.model.StatementImport;
import com.pigpurchases.model.StatementSource;
import com.pigpurchases.model.Transaction;
import com.pigpurchases.model.TransactionMapping;
import com.pigpurchases.parser.ExclusionRule;
import com.pigpurchases.parser.ParsedStatement;
import com.pigpurchases.parser.ParsedTransaction;
import com.pigpurchases.parser.StatementParser;
import com.pigpurchases.parser.StatementParserRegistry;
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
import java.util.Optional;

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

    /**
     * Every parser on the classpath, which is not necessarily the same set in every
     * checkout — see {@link StatementParserRegistry}.
     */
    @Autowired
    private StatementParserRegistry parserRegistry;

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

        debugLog.info("ingest", "Loading " + file.getFileName() + " for source \"" + source.getName()
                + "\" using parser " + rules.path("parser").asText("") + ".");

        ParsedStatement statement;
        try {
            statement = parser.parse(file);
        } catch (RuntimeException e) {
            // The loudest guards in the codebase throw from inside the parser — Crestline with
            // no Opening/Closing Date, Ridgeline with no dated ledger row. They wrote
            // nothing here, so the Debug screen ended at "Loading …" with no record of the
            // refusal, contradicting this class's promise that every ingest leaves a trail.
            debugLog.error("ingest", "Refused " + file.getFileName() + ": " + e.getMessage());
            throw e;
        }
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
        Optional<StatementImport> prior =
                statementImportRepository.findByStatementSourceIdAndStatementDate(source.getId(), statementDate);

        // NOTHING MAY REPLACE SOMETHING. Enforced here, where the consequence lives, rather
        // than in reconcile — which can only refuse an empty parse for a parser that prints
        // control totals. Ridgeline prints none (its "total" is derived from the very
        // rows being checked), so a layout change that stopped its utility rows matching
        // returned zero transactions, passed reconcile's fallthrough, and then deleted the
        // month's real utilities and replaced them with nothing, reporting success.
        //
        // Stated as a rule about outcomes, this cannot be got round by a parser that has no
        // way to verify itself, and it still allows a genuine first-time load of an empty
        // statement.
        if (statement.getTransactions().isEmpty() && prior.isPresent()
                && !transactionRepository.findByStatementImportId(prior.get().getId()).isEmpty()) {
            fail(file.getFileName().toString(), "the parse found no transactions at all, but the"
                    + " statement already loaded for " + statementDate + " has them. Refusing to"
                    + " replace real data with nothing — the parser has probably stopped matching"
                    + " this layout");
        }

        Map<String, Deque<CarriedMapping>> carried = new LinkedHashMap<>();
        List<AnalysisRunSource> consumingLinks = new ArrayList<>();
        prior.ifPresent(prev -> {
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

        int priorDecisions = carried.values().stream().mapToInt(Deque::size).sum();

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
            CarriedMapping carriedForRow = queue == null ? null : queue.poll();
            if (carriedForRow != null) {
                mappingRepository.save(new TransactionMapping(carriedForRow.runId(), txn.getId(),
                        carriedForRow.budgetEntryId(), carriedForRow.status(), carriedForRow.reason()));
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

        if (priorDecisions > 0) {
            // What is LEFT in the queues after the loop is old decisions that found no
            // matching row in the new read — the categorizations this re-read lost.
            int stranded = carried.values().stream().mapToInt(Deque::size).sum();
            debugLog.info("ingest", "Replaced the earlier import of " + statementDate + ": "
                    + (priorDecisions - stranded) + " of " + priorDecisions
                    + " existing categorization(s) carried onto the new rows"
                    + (stranded > 0 ? ", " + stranded + " lost because those lines changed" : "")
                    // Driven off `stranded`, not off `unmatchedRows`. With zero new rows the
                    // insert loop never runs, so unmatchedRows stays false and this claimed
                    // "every row kept its category" for an import that kept nothing.
                    + (unmatchedRows
                        ? ". Some rows have no categorization, so this statement is being re-mapped."
                        : stranded == 0 ? ". Every row kept its category." : "."));
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

        debugLog.info("ingest", "Loaded " + imp.getFileName() + " (statement date " + statementDate
                + "): " + statement.getTransactions().size() + " transaction(s), " + excludedCount
                + " excluded from spend by this source's rules.");

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
            // Warn, but DO NOT return: the control-total checks below must still run.
            //
            // Returning here inverted the whole guarantee. One row out of forty failing to
            // match made the totals disagree and refused the import; all forty failing —
            // the worse case, and the likelier one, since a layout change breaks every row
            // at once — produced an empty list that skipped every check and was accepted.
            // The summary box is plain label-and-value text and keeps parsing long after
            // the multi-column activity rows stop, so the exact scenario reconciliation
            // exists for was the one it did not cover.
            //
            // Worse than a wrong number: control flow then reached the idempotency block,
            // which deleted the previous good import and its transactions and replaced them
            // with nothing. That account's spend silently became zero for the month and the
            // UI reported success with "Transactions: 0".
            //
            // A genuinely empty cycle still passes: Crestline prints Purchases +$0.00 and Bayside's
            // ending equals its beginning, so the expected totals are zero and match an
            // empty sum. Only a non-zero printed total against zero parsed rows now refuses,
            // which is precisely the case that should.
            debugLog.warn("ingest", "No transactions parsed from " + fileName
                    + " (parser " + parserId + "). If the statement is not genuinely empty,"
                    + " the parser no longer matches this layout.");
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
            // Fees and interest are totalled on their own summary lines, separate from
            // Purchases — but whether they ALSO appear as dated rows in the activity table
            // (and so land in parsedPurchases) varies by statement layout, and cannot be
            // determined without reading a statement that carries them.
            //
            // So accept any combination rather than guessing one. Adding them
            // unconditionally refused every cycle where they print undated; adding none
            // refused every cycle where they print dated. Both are the same
            // false-positive-blocks-real-work failure, just on opposite layouts. A genuine
            // mis-parse still fails, since it would have to coincide exactly with one of
            // these four sums.
            BigDecimal fees = orZero(statement.control("fees"));
            BigDecimal interest = orZero(statement.control("interest"));
            if (purchases != null) {
                List<BigDecimal> acceptable = List.of(
                        purchases,
                        purchases.add(fees),
                        purchases.add(interest),
                        purchases.add(fees).add(interest));
                boolean ties = acceptable.stream().anyMatch(v -> parsedPurchases.compareTo(v) == 0);
                // Say WHICH combination tied. The tolerance exists because the layout is
                // unknown, but it also means a fee row the parser dropped ties the
                // purchases-only value and passes — so if the statement prints a non-zero
                // fee or interest and the rows did not account for it, that has to be
                // visible here rather than silently accepted.
                boolean feesAccountedFor = fees.signum() == 0
                        || parsedPurchases.compareTo(purchases.add(fees)) == 0
                        || parsedPurchases.compareTo(purchases.add(fees).add(interest)) == 0;
                boolean interestAccountedFor = interest.signum() == 0
                        || parsedPurchases.compareTo(purchases.add(interest)) == 0
                        || parsedPurchases.compareTo(purchases.add(fees).add(interest)) == 0;
                if (ties && !(feesAccountedFor && interestAccountedFor)) {
                    debugLog.warn("ingest", "Reconciled " + fileName + " against purchases alone."
                            + " The statement also prints fees " + fees + " and interest " + interest
                            + ", and no parsed row accounts for them — either they are printed"
                            + " undated (expected) or the parser is dropping those rows.");
                }
                if (!ties) {
                    fail(fileName, "positive rows total " + parsedPurchases
                            + " but the statement prints purchases " + purchases
                            + ", fees " + fees + ", interest " + interest
                            + " (no combination of those matches)");
                }
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

        // Fallback for Bayside when the balance pair did not parse. Both totals are printed on
        // the statement and were already being extracted; nothing read them, so a summary
        // line that failed to match silently downgraded the whole check to nothing — and a
        // summary line fails for the same reason a transaction row does, a layout change.
        BigDecimal totalDeposits = statement.control("totalDeposits");
        BigDecimal totalDebits = statement.control("totalDebits");
        if (totalDeposits != null || totalDebits != null) {
            BigDecimal parsedIn = sumWhere(statement, true);
            BigDecimal parsedOut = sumWhere(statement, false);
            if (totalDeposits != null && parsedIn.compareTo(totalDeposits) != 0) {
                fail(fileName, "deposits total " + parsedIn + " but the statement prints " + totalDeposits);
            }
            if (totalDebits != null && parsedOut.compareTo(totalDebits) != 0) {
                fail(fileName, "withdrawals total " + parsedOut + " but the statement prints " + totalDebits);
            }
            debugLog.info("ingest", "Reconciled " + fileName + ": " + statement.getTransactions().size()
                    + " transactions against the printed deposit and withdrawal totals"
                    + " (the balance pair was unavailable).");
            return;
        }

        if (parserRegistry.get(parserId).printsControlTotals()) {
            // This parser prints totals and none of them parsed, which means the summary box
            // did not match either — the same layout change that makes transaction rows go
            // missing. Refusing beats storing an unverifiable parse from a format that is
            // supposed to be verifiable.
            fail(fileName, "none of the statement's control totals could be read, so the parse"
                    + " cannot be verified. The layout has probably changed");
        }

        // This parser declares that it prints no INDEPENDENT total — one derived from the
        // very rows being checked cannot catch anything, so such a parser carries a
        // structural guard of its own instead. Say so rather than implying the parse was
        // verified. See StatementParser.printsControlTotals.
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

    /**
     * The parser for a source's configured id.
     *
     * <p>Was a switch naming the three parser classes directly, which meant this file had to
     * be edited to add a parser and would not compile without all of them. Parsers are now
     * discovered as beans, so the set can differ between checkouts — the ones written
     * against real personal statements live outside this repository, and the ones here are
     * demonstration parsers. Nothing in the ingest path knows the difference.
     */
    private StatementParser parserFor(String parserId) {
        return parserRegistry.get(parserId);
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
