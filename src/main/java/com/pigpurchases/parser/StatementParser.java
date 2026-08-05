package com.pigpurchases.parser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/**
 * A parser for one issuer's statement PDFs.
 *
 * <p>Implementations are Spring beans, discovered by {@link StatementParserRegistry} rather
 * than named in a switch. That is what lets a parser be added — or withheld — without
 * touching the ingest path: a set of parsers written against your own statements can live
 * outside this repository entirely and still be dispatched to, provided its classes are on
 * the classpath and inside the scanned package.
 *
 * <p>Parsers must be <b>stateless</b>. They are singletons shared across every ingest, and
 * per-source configuration (which transfers to exclude from spend) is applied by
 * {@code IngestService} after parsing, not held on the parser.
 */
public interface StatementParser {

    /**
     * The parser ids this implementation answers to, as stored in a statement source's
     * {@code parserRules} JSON.
     *
     * <p>A set rather than a single id because one statement layout can serve several
     * account products — the same deposit-account rows appear under two different summary
     * wordings, and both are the same parser.
     *
     * <p>These strings are persisted in the database, so changing one orphans every source
     * that already refers to it. Add a new id to the set instead of renaming.
     */
    Set<String> ids();

    /**
     * Whether this issuer's statements print totals the parse can be checked against.
     *
     * <p>When true, {@code IngestService} refuses a statement whose control totals it could
     * not read at all: a summary box that stopped matching is the same layout change that
     * makes transaction rows go missing, so an unverifiable parse from a format that is
     * supposed to be verifiable is refused rather than stored.
     *
     * <p>Return false only when the statement genuinely prints no <i>independent</i> total.
     * A "total" derived from the very rows being checked cannot catch anything, and treating
     * it as reconciliation would be worse than admitting there is none — such a parser needs
     * a structural guard of its own instead.
     *
     * <p>This lived in {@code IngestService} as a hardcoded set of parser ids. That put
     * knowledge of specific issuers in the ingest path, which had to be edited to add a
     * parser and named those issuers in code that is otherwise parser-agnostic. The parser
     * knows the answer; it should say so.
     */
    default boolean printsControlTotals() {
        return true;
    }

    ParsedStatement parse(Path pdf) throws IOException;
}
