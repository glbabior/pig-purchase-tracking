package com.pigpurchases.web;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards the one failure in {@code index.html} that takes the whole app down at once.
 *
 * <p>A syntax error anywhere in the inline script stops the browser parsing it, so <b>every
 * screen dies together</b> — silently, with no server-side symptom and nothing in the debug
 * log, because none of the code ever runs. Every other frontend defect costs one screen or
 * one number; this one costs all of them, and it is the easiest to introduce, since the file
 * is 2,800 lines edited by hand.
 *
 * <p>The script is PARSED, not executed: no browser, no DOM stubs, no fetch. That is enough
 * to catch a stray brace, an unterminated string or a bad template literal, which is the
 * whole class of problem this exists for. It cannot catch a runtime error — a real browser
 * would be needed for that, and it is a much heavier commitment for a much smaller risk.
 */
class IndexHtmlSyntaxTest {

    private static final Path INDEX = Path.of("src/main/resources/static/index.html");
    private static final Path APP_MATH = Path.of("src/main/resources/static/app-math.js");

    /** The inline block: an opening script tag with no src attribute. */
    private static final Pattern INLINE_SCRIPT =
            Pattern.compile("<script>(.*?)</script>", Pattern.DOTALL);

    private static final Pattern HTML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    /**
     * The page with HTML comments removed. A comment mentioning a script tag in prose would
     * otherwise be picked up as a code block and "fail to parse" — which is exactly what
     * happened the first time this test ran, on a comment written two commits earlier.
     */
    private static String markupWithoutComments() throws IOException {
        return HTML_COMMENT.matcher(Files.readString(INDEX)).replaceAll("");
    }

    @Test
    void theInlineScriptParses() throws IOException {
        Matcher m = INLINE_SCRIPT.matcher(markupWithoutComments());

        int blocks = 0;
        try (Context context = Context.create("js")) {
            while (m.find()) {
                blocks++;
                String js = m.group(1);
                try {
                    // parse(), not eval(): this checks the syntax without running anything,
                    // so document/window/fetch never need to exist.
                    context.parse(Source.newBuilder("js", js, "index.html#inline" + blocks)
                            .buildLiteral());
                } catch (PolyglotException e) {
                    fail("index.html has a JavaScript syntax error — the browser would stop"
                            + " parsing and EVERY screen would be blank. " + e.getMessage());
                }
            }
        }
        assertTrue(blocks > 0, "found no inline <script> block; has the page been restructured?");
    }

    @Test
    void appMathParses() throws IOException {
        try (Context context = Context.create("js")) {
            try {
                context.parse(Source.newBuilder("js", Files.readString(APP_MATH), "app-math.js")
                        .buildLiteral());
            } catch (PolyglotException e) {
                fail("app-math.js has a JavaScript syntax error: " + e.getMessage());
            }
        }
    }

    /**
     * The page must load app-math.js before the inline script that calls its functions.
     * Getting the order wrong is not a syntax error — it fails at runtime, on first use,
     * which is exactly the kind of thing parsing cannot see.
     */
    @Test
    void appMathIsLoadedBeforeTheCodeThatUsesIt() throws IOException {
        String page = markupWithoutComments();
        int tag = page.indexOf("<script src=\"app-math.js\"></script>");
        int inline = page.indexOf("<script>");

        assertTrue(tag >= 0, "index.html must load app-math.js");
        assertTrue(inline >= 0, "index.html must still have its inline script");
        assertTrue(tag < inline,
                "app-math.js must be loaded BEFORE the inline script, or its functions are"
                        + " undefined when the page first calls them");
    }

    /** One inline block, so the parse test cannot silently miss most of the code. */
    @Test
    void thereIsExactlyOneInlineScriptBlock() throws IOException {
        Matcher m = INLINE_SCRIPT.matcher(markupWithoutComments());
        int blocks = 0;
        while (m.find()) {
            blocks++;
        }
        assertEquals(1, blocks, "index.html is expected to hold one inline script block; if that"
                + " changed deliberately, update this test rather than deleting it");
    }
}
