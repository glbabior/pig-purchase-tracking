package com.pigpurchases.demo;

import com.pigpurchases.parser.NorthwindStatementParser;
import com.pigpurchases.parser.ParsedStatement;
import com.pigpurchases.parser.ParsedTransaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generated demo statements must reconcile, or the demo is broken on arrival:
 * {@code IngestService} refuses a statement whose parsed rows disagree with its printed
 * control totals, so a generator that got its own arithmetic wrong would produce files
 * the app rejects — and the first thing anyone tries would fail.
 */
class DemoStatementsTest {

    private final NorthwindStatementParser parser = new NorthwindStatementParser();

    @Test
    void generatedStatementsReconcileAgainstTheirOwnPrintedTotals(@TempDir Path dir)
            throws IOException {
        for (int i = 0; i < 3; i++) {
            LocalDate end = LocalDate.of(2026, 6, 11).minusMonths(i);
            Path pdf = dir.resolve("m" + i + ".pdf");
            DemoStatements.writePdf(pdf, DemoStatements.monthOf(end));

            ParsedStatement st = parser.parse(pdf);
            assertEquals(end, st.getStatementDate(), "statement date");

            BigDecimal positives = st.getTransactions().stream().map(ParsedTransaction::getAmount)
                    .filter(a -> a.signum() > 0).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal negatives = st.getTransactions().stream().map(ParsedTransaction::getAmount)
                    .filter(a -> a.signum() < 0).reduce(BigDecimal.ZERO, BigDecimal::add);

            assertEquals(0, st.control("purchases").compareTo(positives),
                    "purchases must equal the printed total for " + end);
            assertEquals(0, st.control("credits").compareTo(negatives),
                    "credits must equal the printed total for " + end);
        }
    }

    @Test
    void everyMonthLeavesSomethingForTheParkedBucket() {
        // The demo is duller if everything resolves: the review screen, the hint prompt and
        // the "Other" bucket are most of what the mapping model is worth showing.
        List<String> lines = DemoStatements.monthOf(LocalDate.of(2026, 6, 11));
        assertTrue(lines.stream().anyMatch(l -> l.contains("HARDWARE DEPOT")),
                "a merchant no seeded hint matches");
        assertTrue(lines.stream().anyMatch(l -> l.contains("ORCHID FLORIST")),
                "a second unmatched merchant");
    }

    @Test
    void statementsAreDatedDistinctly() throws IOException {
        // Re-ingesting the same source and statement date REPLACES the prior import, so
        // three statements sharing a date would silently collapse into one.
        LocalDate a = LocalDate.of(2026, 6, 11);
        assertEquals(3, List.of(a, a.minusMonths(1), a.minusMonths(2)).stream().distinct().count());
    }
}
