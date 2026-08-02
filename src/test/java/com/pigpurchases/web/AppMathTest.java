package com.pigpurchases.web;

import com.pigpurchases.model.Transaction;
import com.pigpurchases.service.AnalysisService;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The frontend's pure functions, run for real.
 *
 * <p>Until this existed, every function deciding what number appears on screen was covered
 * by nothing, while its Java counterpart had a test that was checked to fail when reverted.
 * A review pass over `index.html` found eight defects in one sitting, two of them about
 * money being displayed with the wrong sign — which is exactly the class this covers.
 *
 * <p>{@link #signedSpendAgreesWithTheServerForEveryType()} is the important one. That
 * function is a hand-written mirror of {@code AnalysisService.signedSpend}, and a mirror
 * that drifts is worse than no mirror: the screen and the database would disagree with
 * nothing to say so. Here both are executed and compared.
 */
class AppMathTest {

    private static final Path APP_MATH = Path.of("src/main/resources/static/app-math.js");

    private static Context js;

    @BeforeAll
    static void loadTheRealFile() throws IOException {
        assertTrue(Files.exists(APP_MATH), "app-math.js must be where index.html loads it from");
        js = Context.create("js");
        js.eval(Source.newBuilder("js", Files.readString(APP_MATH), "app-math.js").buildLiteral());
    }

    @AfterAll
    static void close() {
        if (js != null) {
            js.close();
        }
    }

    private Value call(String function, Object... args) {
        return js.getBindings("js").getMember(function).execute(args);
    }

    /** A row as the mappings endpoint serves it: amount is a string, type a status word. */
    private Value row(String amount, String type) {
        return js.eval("js", "(function(a, t) { return { amount: a, type: t }; })")
                .execute(amount, type);
    }

    // ---- signedSpend: the mirror that must not drift -----------------------

    @Test
    void signedSpendAgreesWithTheServerForEveryType() {
        // Both stored signs for each type, because the parsers disagree: a Bayside withdrawal
        // is stored negative while a Crestline purchase is positive, which is why the type
        // decides rather than the sign.
        List<String> types = List.of("PURCHASE", "WITHDRAWAL", "CHECK", "FEE",
                                     "PAYMENT", "CREDIT", "DEPOSIT");
        List<String> amounts = List.of("150.00", "-150.00", "0.35", "-0.35", "0.00");

        for (String type : types) {
            for (String amount : amounts) {
                BigDecimal fromServer = serverSignedSpend(amount, type);
                double fromBrowser = call("signedSpend", row(amount, type)).asDouble();

                assertEquals(fromServer.doubleValue(), fromBrowser, 0.0001,
                        "signedSpend disagrees for " + type + " " + amount
                                + " — the screen and the database would show different money");
            }
        }
    }

    /**
     * Calls the server's own implementation, so this cannot pass by re-implementing the
     * Java rule in the test — which would make the comparison meaningless.
     */
    private BigDecimal serverSignedSpend(String amount, String type) {
        Transaction txn = new Transaction(LocalDate.of(2026, 6, 1), "X", "X",
                new BigDecimal(amount), "2026-06");
        txn.setType(type);
        return AnalysisService.signedSpend(txn);
    }

    // ---- the rest ----------------------------------------------------------

    @Test
    void formatCurrencyAlwaysShowsTwoDecimals() {
        assertEquals("$0.00", call("formatCurrency", (Object) null).asString());
        assertEquals("$0.00", call("formatCurrency", "").asString());
        assertEquals("$4.10", call("formatCurrency", "4.1").asString());
        assertEquals("$1230.00", call("formatCurrency", "1230").asString());
        assertEquals("$-150.00", call("formatCurrency", "-150").asString());
        assertEquals("$0.35", call("formatCurrency", 0.35).asString());
    }

    @Test
    void parseDollarAmountAcceptsMoneyAndRejectsEverythingElse() {
        assertEquals("4.10", call("parseDollarAmount", "4.1").asString());
        assertEquals("1230.00", call("parseDollarAmount", "$1,230").asString());
        assertEquals("0.35", call("parseDollarAmount", " 0.35 ").asString());
        assertEquals("1234567.89", call("parseDollarAmount", "$1,234,567.89").asString());

        // Rejected: null rather than NaN is what lets the caller refuse the save.
        // ".35" is in here deliberately — the pattern requires a digit before the point.
        for (String bad : List.of("", "abc", "1.234", "-5.00", "1e3", "5..0", "$", ".35")) {
            assertTrue(call("parseDollarAmount", bad).isNull(),
                    "\"" + bad + "\" is not an amount and must not be saved as one");
        }
    }

    @Test
    void datesAreFormattedFromTheStringNeverThroughDate() {
        // Through `new Date("2026-06-11")` these would shift a day west of Greenwich.
        assertEquals("06/11/26", call("formatMmDdYy", "2026-06-11").asString());
        assertEquals("01/01/26", call("formatMmDdYy", "2026-01-01").asString());
        assertEquals("??", call("formatMmDdYy", (Object) null).asString());
        assertEquals("not-a-date", call("formatMmDdYy", "not-a-date").asString());

        assertEquals("June 2026", call("formatMonthLong", "2026-06").asString());
        assertEquals("December 2025", call("formatMonthLong", "2025-12").asString());
        assertEquals("", call("formatMonthLong", (Object) null).asString());
        assertEquals("2026-13", call("formatMonthLong", "2026-13").asString(),
                "an impossible month must pass through rather than showing undefined");

        assertEquals("07/22 15:53:38", call("formatLogTime", "2026-07-22T15:53:38.53").asString());
        assertEquals("", call("formatLogTime", (Object) null).asString());
    }

    @Test
    void escapeHtmlClosesTheMerchantTextBoundary() {
        assertEquals("&lt;script&gt;", call("escapeHtml", "<script>").asString());
        assertEquals("A &amp; B", call("escapeHtml", "A & B").asString());
        assertEquals("&quot;quoted&quot;", call("escapeHtml", "\"quoted\"").asString());
        // Ampersand first, or the other escapes would be double-escaped.
        assertEquals("&amp;lt;", call("escapeHtml", "&lt;").asString());
    }

    @Test
    void everyFunctionIndexHtmlExpectsIsActuallyDefined() throws IOException {
        // Guards the extraction itself: moving one of these back, renaming it, or dropping
        // the <script> tag would otherwise only show up as a blank screen in the browser.
        String page = Files.readString(Path.of("src/main/resources/static/index.html"));
        assertTrue(page.contains("<script src=\"app-math.js\"></script>"),
                "index.html must still load app-math.js");

        for (String fn : List.of("signedSpend", "formatCurrency", "parseDollarAmount",
                                 "formatMmDdYy", "formatMonthLong", "formatLogTime", "escapeHtml")) {
            assertTrue(js.getBindings("js").getMember(fn) != null,
                    fn + " is called by index.html but is not defined in app-math.js");
            assertTrue(page.contains(fn + "("), fn + " is no longer used by index.html — "
                    + "either it moved back inline, or it is dead and should go");
        }
    }
}
