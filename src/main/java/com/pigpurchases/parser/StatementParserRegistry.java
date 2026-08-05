package com.pigpurchases.parser;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Every {@link StatementParser} on the classpath, indexed by the ids it answers to.
 *
 * <p>This replaced a {@code switch} in {@code IngestService} that named the three parser
 * classes directly. The switch worked, but it meant the ingest path had to be edited to add
 * a parser and would not compile without all of them — so a parser could not be added by a
 * user of this project, and the set of parsers could not vary between one checkout and
 * another. Both matter now: the parsers written against real personal statements are
 * maintained outside this repository, and the ones shipped here are demonstration parsers
 * for generated sample statements. Dispatch resolves whatever is actually present.
 *
 * <p>Two failure modes are made loud rather than left to chance.
 *
 * <p><b>A duplicate id fails at startup.</b> With parsers arriving from more than one source
 * tree, two of them claiming the same id is a real possibility — a private parser and a
 * demo parser for the same account, say. Picking one arbitrarily would silently parse
 * statements with the wrong parser, which is the kind of wrong that reconciles to nothing
 * and gets blamed on the statement. The application refuses to start instead.
 *
 * <p><b>An unknown id names what IS available.</b> The old message was
 * {@code No parser configured for id: ''}, which is the exact error a source created through
 * the UI produces, and it said nothing about what to do. It is still the most likely error
 * here, so it now lists the ids that would have worked.
 */
@Component
public class StatementParserRegistry {

    private final Map<String, StatementParser> byId = new LinkedHashMap<>();

    public StatementParserRegistry(List<StatementParser> parsers) {
        Map<String, String> claimedBy = new LinkedHashMap<>();
        List<String> conflicts = new ArrayList<>();
        for (StatementParser parser : parsers) {
            String owner = parser.getClass().getName();
            Set<String> ids = parser.ids();
            if (ids == null || ids.isEmpty()) {
                throw new IllegalStateException(owner + " declares no parser ids, so nothing"
                        + " can ever dispatch to it. Return at least one id from ids().");
            }
            for (String id : ids) {
                String existing = claimedBy.putIfAbsent(id, owner);
                if (existing != null) {
                    conflicts.add("\"" + id + "\" claimed by both " + existing + " and " + owner);
                    continue;
                }
                byId.put(id, parser);
            }
        }
        if (!conflicts.isEmpty()) {
            throw new IllegalStateException(
                    "Two statement parsers claim the same id, so which one parses a statement"
                    + " would depend on bean ordering: " + String.join("; ", conflicts)
                    + ". Give one of them a different id, or take it off the classpath.");
        }
    }

    /** The parser for an id, or an exception naming the ids that do exist. */
    public StatementParser get(String parserId) {
        StatementParser parser = byId.get(parserId);
        if (parser != null) {
            return parser;
        }
        String available = byId.isEmpty()
                ? "none — no statement parsers are on the classpath at all"
                : String.join(", ", ids());
        throw new IllegalArgumentException("No parser configured for id: '" + parserId
                + "'. Available: " + available + ". A statement source's parser is set through"
                + " PUT /api/statement-sources/{id}/parser-rules.");
    }

    /** Every known id, sorted, for error messages and for offering a choice in the UI. */
    public Set<String> ids() {
        return Collections.unmodifiableSet(new TreeSet<>(byId.keySet()));
    }
}
