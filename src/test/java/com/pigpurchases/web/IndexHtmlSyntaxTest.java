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

    /**
     * A browser-shaped stub: every property of everything is a function returning itself.
     *
     * <p>Enough for {@code document.getElementById(x).addEventListener(...)} and the rest of
     * the init code to run without a DOM. {@code then} is deliberately absent so an
     * {@code await} on a stubbed {@code fetch} resolves instead of hanging on a fake thenable.
     */
    private static final String DOM_STUB = """
        var stub = new Proxy(function () {}, {
          get: function (t, k) {
            if (k === 'then') return undefined;
            if (k === Symbol.toPrimitive) return function () { return ''; };
            if (k === 'length') return 0;
            return stub;
          },
          apply: function () { return stub; },
          construct: function () { return stub; },
          set: function () { return true; },
          has: function () { return true; }
        });
        var document = stub, window = stub, navigator = stub, localStorage = stub;
        var fetch = function () { return stub; };
        var alert = function () {}, confirm = function () { return false; };
        var setTimeout = function () {}, setInterval = function () {};
        var console = { log: function () {}, error: function () {}, warn: function () {} };
        """;

    /**
     * The inline script RUNS to completion.
     *
     * <p>Parsing is not enough. A `let` referenced by init code that sits above its
     * declaration is perfectly valid syntax and throws at runtime — the temporal dead zone —
     * and it takes the page down exactly as hard as a stray brace: init aborts, no listener
     * binds, every screen is inert. That shipped once, and parsing had nothing to say about
     * it.
     *
     * <p>This does not need a real browser, only a permissive stub, so the "much heavier
     * commitment" this file used to cite against runtime checks does not apply. What it
     * covers is narrow but exact: the top-level statements that run when the page loads.
     */
    @Test
    void theInlineScriptRunsWithoutThrowing() throws IOException {
        Matcher m = INLINE_SCRIPT.matcher(markupWithoutComments());
        assertTrue(m.find(), "found no inline <script> block");

        try (Context context = Context.create("js")) {
            context.eval(Source.newBuilder("js", Files.readString(APP_MATH), "app-math.js")
                    .buildLiteral());
            context.eval(Source.newBuilder("js", DOM_STUB, "dom-stub.js").buildLiteral());
            try {
                context.eval(Source.newBuilder("js", m.group(1), "index.html#inline")
                        .buildLiteral());
            } catch (PolyglotException e) {
                fail("index.html's inline script throws while initialising, so no event handler"
                        + " is ever bound and EVERY screen is dead: " + e.getMessage());
            }
        }
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
