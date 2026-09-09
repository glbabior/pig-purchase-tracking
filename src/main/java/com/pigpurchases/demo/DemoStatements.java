package com.pigpurchases.demo;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates Northwind Bank statements — a bank that does not exist, in a format this
 * project invents — so the app can be demonstrated with no real data.
 *
 * <p><b>The totals are computed, not typed.</b> {@code IngestService} refuses a statement
 * whose parsed rows disagree with the totals it prints, so a generator with a hardcoded
 * total would produce files the app rejects the moment a row changed. Summing the rows
 * here means the demo statements are always internally consistent, and it means the
 * reconciliation really is doing something rather than being satisfied by construction.
 */
final class DemoStatements {

    private DemoStatements() {}

    private static final DateTimeFormatter ROW = DateTimeFormatter.ofPattern("MM/dd/yy");
    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("MM/dd/yyyy");

    /**
     * One line of a generated statement.
     *
     * @param amount the amount in a typical month; scaled per month unless {@code fixed}
     * @param fixed  a charge that is the same every month — a subscription. Swinging one
     *               would be the one thing a reader knows is wrong.
     */
    record Txn(int dayOfMonth, String description, String amount, boolean fixed) {
        Txn(int dayOfMonth, String description, String amount) {
            this(dayOfMonth, description, amount, false);
        }
    }

    /**
     * The merchants a demo month is built from, at typical-month amounts.
     *
     * <p>Most are placed by the seeded budget entries' hints, so a mapping run visibly
     * categorizes them. The last two deliberately match nothing: they land in "Other",
     * which is what makes the parked bucket and the review screen worth opening.
     */
    private static final List<Txn> MONTHLY = List.of(
            new Txn(3,  "FRESH MARKET GROCERY ANYTOWN CA", "142.18"),
            new Txn(5,  "DAILY GRIND COFFEE ANYTOWN CA",   "6.75"),
            new Txn(8,  "CITY POWER AND WATER",            "88.40"),
            new Txn(11, "METRO TRANSIT FARE ANYTOWN CA",   "42.00"),
            new Txn(14, "TAQUERIA LUNA ANYTOWN CA",        "31.60"),
            new Txn(16, "STREAMFLIX MONTHLY",              "15.99", true),
            new Txn(19, "FRESH MARKET GROCERY ANYTOWN CA", "97.32"),
            new Txn(22, "DAILY GRIND COFFEE ANYTOWN CA",   "5.25"),
            new Txn(24, "PIZZA NIGHT ANYTOWN CA",          "28.45"),
            new Txn(26, "HARDWARE DEPOT ANYTOWN CA",       "63.10"),
            new Txn(28, "ORCHID FLORIST ANYTOWN CA",       "54.00"));

    /**
     * How far a month departs from those typical amounts.
     *
     * <p>Every generated month used to be a copy of the same one, so the Rolling
     * screen's trend chart drew a flat line and its bars came out identical. That
     * screen's entire subject is how spending moves between months, and the demo was
     * demonstrating that it never does.
     *
     * <p>Deterministic, not random: a given month always generates the same statement,
     * so a demo can be torn down and rebuilt identically and a screenshot retaken.
     * Each row starts at a different position in the ring, so categories move
     * independently rather than every amount rising and falling in step — which would
     * be just as unreal as no movement at all, and would leave the per-category chart
     * a rescaled copy of the total.
     */
    private static final double[] SWING = {1.00, 0.81, 1.27, 0.90, 1.16, 0.74};

    /** Written on the 20th of each month, and the only money-in row. */
    private static final String PAYMENT = "PAYMENT THANK YOU";

    /**
     * The text of one month's statement, with its control totals summed from its own rows.
     *
     * @param periodEnd the statement's closing date; rows fall in the calendar month before
     */
    static List<String> monthOf(LocalDate periodEnd) {
        LocalDate rowMonth = periodEnd.minusMonths(1);
        List<String> rows = new ArrayList<>();
        BigDecimal purchases = BigDecimal.ZERO;
        for (int i = 0; i < MONTHLY.size(); i++) {
            Txn t = MONTHLY.get(i);
            LocalDate date = rowMonth.withDayOfMonth(Math.min(t.dayOfMonth(), rowMonth.lengthOfMonth()));
            String amount = amountFor(t, rowMonth, i);
            rows.add(date.format(ROW) + " " + t.description() + " " + amount);
            purchases = purchases.add(new BigDecimal(amount));
        }
        BigDecimal credits = new BigDecimal("250.00");
        rows.add(rowMonth.withDayOfMonth(20).format(ROW) + " " + PAYMENT + " -" + credits.toPlainString());

        List<String> lines = new ArrayList<>();
        lines.add("Northwind Bank - Account Statement");
        lines.add("Statement Period Ending " + periodEnd.format(PERIOD));
        lines.add("Total Purchases $" + purchases.toPlainString());
        lines.add("Total Credits $" + credits.toPlainString());
        lines.addAll(rows);
        lines.add("Thank you for banking with Northwind");
        return lines;
    }

    /**
     * This row's amount in this month: the typical amount moved by the month's swing.
     *
     * <p>Rounded to the cent here, before the row is written, because the statement's
     * printed totals are summed from these same strings — computing the total from
     * unrounded values would print a control total the parsed rows cannot reproduce,
     * and {@code IngestService} would refuse the file.
     */
    private static String amountFor(Txn t, LocalDate rowMonth, int row) {
        if (t.fixed()) {
            return t.amount();
        }
        int step = Math.floorMod(rowMonth.getMonthValue() + row, SWING.length);
        return new BigDecimal(t.amount())
                .multiply(BigDecimal.valueOf(SWING[step]))
                .setScale(2, RoundingMode.HALF_UP)
                .toPlainString();
    }

    /** Write the lines as a one-page PDF the Northwind parser can read. */
    static void writePdf(Path file, List<String> lines) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                cs.setLeading(14);
                cs.newLineAtOffset(50, 740);
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
