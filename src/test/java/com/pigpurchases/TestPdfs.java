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

    /** A minimal Crestline-format statement whose transactions the Crestline parser recognizes. */
    public static final List<String> CHASE_LINES = List.of(
            "Opening/Closing Date 05/12/26 - 06/11/26",
            "Payment, Credits -$150.00",
            "Purchases +$1,234.45",
            "05/22 COFFEE SHOP ANYTOWN CA 4.10",
            "05/23 BIG PURCHASE STORE CITY CA 1,230.00",
            "05/24 METRO STATION CITY CA .35",
            "05/20 & Payment Thank You Bill Pay Service -150.00");

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
