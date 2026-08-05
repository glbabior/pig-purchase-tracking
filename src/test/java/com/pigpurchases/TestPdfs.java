package com.pigpurchases;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Test helper: write a simple one-line-per-entry PDF so tests never need real statements. */
public final class TestPdfs {

    private TestPdfs() {}

    /**
     * A Northwind (demonstration) statement whose rows the Northwind parser recognizes, and
     * whose printed totals the parsed rows reconcile against.
     *
     * <p>Purchases 4.10 + 1,230.00 + 0.35 = 1,234.45; credits -150.00. Change a row and the
     * totals must change with it, or ingest will refuse the file — which is the behaviour
     * these fixtures exist to exercise.
     */
    public static final List<String> NORTHWIND_LINES = List.of(
            "Northwind Bank - Account Statement",
            "Statement Period Ending 06/11/2026",
            "Total Purchases $1,234.45",
            "Total Credits $150.00",
            "05/22/26 COFFEE SHOP ANYTOWN CA 4.10",
            "05/23/26 BIG PURCHASE STORE CITY CA 1,230.00",
            "05/24/26 METRO STATION CITY CA 0.35",
            "05/20/26 PAYMENT THANK YOU -150.00");

    public static void write(Path file, List<String> lines) throws IOException {
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
