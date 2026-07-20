package com.pigpurchases.service;

import com.pigpurchases.model.StatementImport;
import com.pigpurchases.model.StatementSource;
import com.pigpurchases.model.Transaction;
import com.pigpurchases.repository.StatementImportRepository;
import com.pigpurchases.repository.StatementSourceRepository;
import com.pigpurchases.repository.TransactionRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end ingest test that generates its own PDF (no real statements), so it
 * runs anywhere including CI. Covers parser dispatch by rules, storage, uniform
 * exclusion application, the import record, and idempotent re-ingest.
 */
@SpringBootTest
@ActiveProfiles("test")
class IngestServiceIntegrationTest {

    @Autowired private IngestService ingestService;
    @Autowired private StatementSourceRepository sourceRepo;
    @Autowired private TransactionRepository txnRepo;
    @Autowired private StatementImportRepository importRepo;

    private static final List<String> CHASE_PDF_LINES = List.of(
            "Opening/Closing Date 05/12/26 - 06/11/26",
            "Payment, Credits -$150.00",
            "Purchases +$1,234.45",
            "05/22 COFFEE SHOP ANYTOWN CA 4.10",
            "05/23 BIG PURCHASE STORE CITY CA 1,230.00",
            "05/24 METRO STATION CITY CA .35",
            "05/20 & Payment Thank You Bill Pay Service -150.00");

    @Test
    void ingestStoresTransactionsAppliesExclusionsAndIsIdempotent(@TempDir Path dir) throws IOException {
        Path pdf = dir.resolve("crestline-june.pdf");
        writePdf(pdf, CHASE_PDF_LINES);

        StatementSource source = new StatementSource("Crestline Test", dir.toString());
        source.setParserRules("{\"parser\":\"card-pdf\","
                + "\"excludeFromSpend\":[{\"contains\":\"BIG PURCHASE\",\"reason\":\"test transfer\"}]}");
        source = sourceRepo.save(source);

        IngestService.IngestResult result = ingestService.ingest(source, pdf);
        assertEquals(LocalDate.of(2026, 6, 11), result.statementDate());
        assertEquals(4, result.transactionCount());
        assertEquals(1, result.excludedCount());

        List<Transaction> stored = txnRepo.findByStatementSourceId(source.getId());
        assertEquals(4, stored.size());
        assertTrue(stored.stream().allMatch(t -> "2026-06".equals(t.getMonth())));
        List<Transaction> excluded = stored.stream().filter(Transaction::isExcludeFromSpend).toList();
        assertEquals(1, excluded.size());
        assertTrue(excluded.get(0).getDescription().contains("BIG PURCHASE"));

        List<StatementImport> imports = importRepo.findByStatementSourceIdOrderByStatementDateDesc(source.getId());
        assertEquals(1, imports.size());
        assertEquals(4, imports.get(0).getTransactionCount());

        // Re-ingest the same file: replaces, does not duplicate.
        ingestService.ingest(source, pdf);
        assertEquals(4, txnRepo.findByStatementSourceId(source.getId()).size());
        assertEquals(1, importRepo.findByStatementSourceIdOrderByStatementDateDesc(source.getId()).size());
    }

    private static void writePdf(Path file, List<String> lines) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                cs.setLeading(14);
                cs.newLineAtOffset(50, 720);
                for (String line : lines) {
                    cs.showText(line);
                    cs.newLine();
                }
                cs.endText();
            }
            doc.save(file.toFile());
        }
    }
}
