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
import java.util.ArrayList;
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

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** A prior mapping, held by transaction *content* so it can outlive the row's id. */
    private record CarriedMapping(Long runId, TransactionMapping.Status status,
                                  Long budgetEntryId, String reason) {}

    /**
     * Identifies a transaction by what it is rather than by its id: date, absolute
     * amount, and normalized description. Re-ingesting the same statement produces the
     * same key for the same line, which is what lets a mapping survive the replacement.
     */
    private static String contentKey(LocalDate date, BigDecimal amount, String description) {
        return date + "|" + (amount == null ? "" : amount.abs().toPlainString())
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
        String month = statementDate != null ? statementDate.toString().substring(0, 7) : null;

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
        // keeps its categorization; one that changed loses it and simply maps again.
        Map<String, CarriedMapping> carried = new LinkedHashMap<>();
        List<AnalysisRunSource> consumingLinks = new ArrayList<>();
        statementImportRepository.findByStatementSourceIdAndStatementDate(source.getId(), statementDate)
                .ifPresent(prev -> {
                    for (Transaction old : transactionRepository.findByStatementImportId(prev.getId())) {
                        for (TransactionMapping m : mappingRepository.findByTransactionId(old.getId())) {
                            carried.putIfAbsent(
                                    contentKey(old.getTransactionDate(), old.getAmount(), old.getDescription()),
                                    new CarriedMapping(m.getAnalysisRunId(), m.getStatus(),
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

            CarriedMapping prior = carried.get(contentKey(txn.getTransactionDate(), txn.getAmount(), txn.getDescription()));
            if (prior != null) {
                mappingRepository.save(new TransactionMapping(prior.runId(), txn.getId(),
                        prior.budgetEntryId(), prior.status(), prior.reason()));
            }
        }

        // Re-point the run that consumed the old import, so it stays reachable from the
        // Mapping screen and can be re-run or deleted like any other.
        for (AnalysisRunSource link : consumingLinks) {
            link.setStatementImportId(imp.getId());
            runSourceRepository.save(link);
            mappingService.recount(link.getAnalysisRunId());
        }

        return new IngestResult(imp.getId(), statementDate, imp.getFileName(),
                statement.getTransactions().size(), excludedCount);
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
