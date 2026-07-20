package com.pigpurchases.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pigpurchases.model.StatementImport;
import com.pigpurchases.model.StatementSource;
import com.pigpurchases.model.Transaction;
import com.pigpurchases.parser.DepositStatementParser;
import com.pigpurchases.parser.CardStatementParser;
import com.pigpurchases.parser.ExclusionRule;
import com.pigpurchases.parser.PropertyStatementParser;
import com.pigpurchases.parser.ParsedStatement;
import com.pigpurchases.parser.ParsedTransaction;
import com.pigpurchases.parser.StatementParser;
import com.pigpurchases.repository.StatementImportRepository;
import com.pigpurchases.repository.TransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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

    private final ObjectMapper objectMapper = new ObjectMapper();

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

        // Idempotent: drop any prior import for this source + statement date.
        statementImportRepository.findByStatementSourceIdAndStatementDate(source.getId(), statementDate)
                .ifPresent(prev -> {
                    transactionRepository.deleteByStatementImportId(prev.getId());
                    statementImportRepository.delete(prev);
                });

        StatementImport imp = statementImportRepository.save(new StatementImport(
                source.getId(), statementDate, file.getFileName().toString(),
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
