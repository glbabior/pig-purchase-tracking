package com.pigpurchases.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for <b>Northwind Bank</b> statements — a bank that does not exist.
 *
 * <p>This is a demonstration parser, and the format it reads is invented. It exists so the
 * project ships something you can actually run: generated sample statements, a parser that
 * reads them, and an app to ingest them into.
 *
 * <p>It is a demonstration, not a toy. It does the two things every parser here is expected
 * to do, because those are the parts worth copying:
 *
 * <ul>
 *   <li><b>It prints control totals and is reconciled against them.</b> The statement states
 *       its own purchase and credit totals, and {@code IngestService} refuses the import
 *       when the parsed rows disagree. A parser that silently drops a line is the failure
 *       mode that matters — the money is simply missing from a month, and nothing says so.</li>
 *   <li><b>It refuses rather than guesses.</b> No statement-date line means no month to
 *       group by, so it throws instead of returning a statement dated to nothing.</li>
 * </ul>
 *
 * <p>The layout:
 *
 * <pre>
 *   Northwind Bank - Account Statement
 *   Statement Period Ending 06/11/2026
 *   Total Purchases $1,234.45
 *   Total Credits $150.00
 *   05/22/26 COFFEE SHOP ANYTOWN CA 4.10
 *   05/20/26 PAYMENT THANK YOU -150.00
 * </pre>
 *
 * <p>Amounts are signed as printed: purchases positive, payments and refunds negative.
 * Unlike the real card statements this format is modelled on, every row carries a full
 * two-digit year, so there is no year to infer and no December-to-January wrap to get
 * wrong — a deliberate simplification, since that particular trap is worth reading about
 * in a comment rather than reproducing in a demo.
 */
@Component
public class NorthwindStatementParser implements StatementParser {

    /** The id a sample statement source is configured with. See {@code demo} setup. */
    @Override
    public Set<String> ids() {
        return Set.of("northwind-demo-pdf");
    }

    private static final Pattern TXN =
            Pattern.compile("^(\\d{2})/(\\d{2})/(\\d{2})\\s+(.+?)\\s+(-?[\\d,]*\\.\\d{2})$");
    private static final Pattern PERIOD_END =
            Pattern.compile("Statement Period Ending\\s+(\\d{2}/\\d{2}/\\d{4})");
    private static final Pattern TOTAL_PURCHASES =
            Pattern.compile("^Total Purchases\\s+\\+?\\$([\\d,]+\\.\\d{2})");
    private static final Pattern TOTAL_CREDITS =
            Pattern.compile("^Total Credits\\s+-?\\$([\\d,]+\\.\\d{2})");
    private static final DateTimeFormatter PERIOD_DATE =
            DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.ENGLISH);

    @Override
    public ParsedStatement parse(Path pdf) throws IOException {
        String text;
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            text = stripper.getText(document);
        }
        return parseText(text);
    }

    /** Exposed for testing against extracted text without building a file. */
    ParsedStatement parseText(String text) {
        String[] lines = text.split("\\R");

        LocalDate statementDate = null;
        BigDecimal totalPurchases = null;
        BigDecimal totalCredits = null;

        for (String raw : lines) {
            String line = raw.trim();
            Matcher p = PERIOD_END.matcher(line);
            if (p.find() && statementDate == null) {
                statementDate = LocalDate.parse(p.group(1), PERIOD_DATE);
            }
            Matcher tp = TOTAL_PURCHASES.matcher(line);
            if (tp.find() && totalPurchases == null) {
                totalPurchases = money(tp.group(1));
            }
            Matcher tc = TOTAL_CREDITS.matcher(line);
            if (tc.find() && totalCredits == null) {
                totalCredits = money(tc.group(1)).negate();
            }
        }

        if (statementDate == null) {
            // The same refusal the real parsers make, for the same reason: a statement with
            // no date has no month to group by, and an undated import once became the
            // idempotency key — where a null matched the previous undated statement and
            // deleted its transactions.
            throw new IllegalStateException(
                    "Northwind statement has no \"Statement Period Ending\" line, so its "
                    + "transactions have no month. Refusing to parse rather than storing them "
                    + "against no date.");
        }

        List<ParsedTransaction> transactions = new ArrayList<>();
        for (String raw : lines) {
            String line = raw.trim();
            Matcher m = TXN.matcher(line);
            if (!m.matches()) {
                continue;
            }
            int month = Integer.parseInt(m.group(1));
            int day = Integer.parseInt(m.group(2));
            int year = 2000 + Integer.parseInt(m.group(3));
            if (month < 1 || month > 12 || day < 1 || day > 31) {
                continue;
            }
            String description = m.group(4).trim();
            BigDecimal amount = money(m.group(5));

            ParsedTransaction.Type type;
            if (amount.signum() < 0) {
                type = description.toLowerCase(Locale.ROOT).contains("payment")
                        ? ParsedTransaction.Type.PAYMENT
                        : ParsedTransaction.Type.CREDIT;
            } else {
                type = ParsedTransaction.Type.PURCHASE;
            }
            transactions.add(new ParsedTransaction(
                    LocalDate.of(year, month, day), description, cleanVendor(description), amount, type));
        }

        Map<String, BigDecimal> controlTotals = new LinkedHashMap<>();
        controlTotals.put("purchases", totalPurchases);
        controlTotals.put("credits", totalCredits);
        return new ParsedStatement(statementDate, transactions, controlTotals);
    }

    /** Drop a trailing city/state so the vendor is the merchant, as the real parsers do. */
    private static String cleanVendor(String description) {
        String vendor = description.replaceAll("\\s+[A-Z]{2}$", "").trim();
        return vendor.isEmpty() ? description : vendor;
    }

    private static BigDecimal money(String raw) {
        return new BigDecimal(raw.replace(",", ""));
    }
}
