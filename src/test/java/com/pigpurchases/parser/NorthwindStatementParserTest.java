package com.pigpurchases.parser;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the demonstration parser against invented statement text. Portable: it needs no
 * files and no personal data, which is true of every test in this repository.
 */
class NorthwindStatementParserTest {

    private final NorthwindStatementParser parser = new NorthwindStatementParser();

    private static String statement(String... lines) {
        return String.join("\n", lines);
    }

    @Test
    void readsDateRowsAndControlTotals() {
        ParsedStatement st = parser.parseText(statement(
                "Northwind Bank - Account Statement",
                "Statement Period Ending 06/11/2026",
                "Total Purchases $1,234.45",
                "Total Credits $150.00",
                "05/22/26 COFFEE SHOP ANYTOWN CA 4.10",
                "05/23/26 BIG PURCHASE STORE CITY CA 1,230.00",
                "05/24/26 METRO STATION CITY CA 0.35",
                "05/20/26 PAYMENT THANK YOU -150.00"));

        assertEquals(LocalDate.of(2026, 6, 11), st.getStatementDate());
        assertEquals(4, st.getTransactions().size());
        assertEquals(0, new BigDecimal("1234.45").compareTo(st.control("purchases")));
        assertEquals(0, new BigDecimal("-150.00").compareTo(st.control("credits")));

        // The totals are what IngestService reconciles the rows against, so they have to
        // agree here or the fixture is lying about what a good statement looks like.
        BigDecimal positives = st.getTransactions().stream().map(ParsedTransaction::getAmount)
                .filter(a -> a.signum() > 0).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, st.control("purchases").compareTo(positives));
    }

    @Test
    void typesMoneyInByWhetherItIsAPayment() {
        ParsedStatement st = parser.parseText(statement(
                "Statement Period Ending 06/11/2026",
                "05/20/26 PAYMENT THANK YOU -150.00",
                "05/21/26 REFUND FROM COFFEE SHOP -4.10"));

        // The distinction is load-bearing downstream: AnalysisService keeps PAYMENT out of
        // the spend buckets entirely, while a CREDIT nets against its category as a refund.
        assertEquals(ParsedTransaction.Type.PAYMENT, st.getTransactions().get(0).getType());
        assertEquals(ParsedTransaction.Type.CREDIT, st.getTransactions().get(1).getType());
    }

    @Test
    void refusesAStatementWithNoDate() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> parser.parseText(statement("05/22/26 COFFEE SHOP ANYTOWN CA 4.10")));
        assertTrue(e.getMessage().contains("Statement Period Ending"), e.getMessage());
    }

    @Test
    void ignoresLinesThatAreNotTransactions() {
        ParsedStatement st = parser.parseText(statement(
                "Northwind Bank - Account Statement",
                "Statement Period Ending 06/11/2026",
                "Questions? Call 1-800-000-0000",
                "05/22/26 COFFEE SHOP ANYTOWN CA 4.10",
                "Thank you for banking with Northwind"));
        assertEquals(1, st.getTransactions().size());
    }

    @Test
    void declaresItsIdAndThatItReconciles() {
        assertEquals(List.of("northwind-demo-pdf"), List.copyOf(parser.ids()));
        // Its statements print independent totals, so a parse that cannot be checked
        // against them is refused by IngestService rather than stored.
        assertTrue(parser.printsControlTotals());
    }
}
