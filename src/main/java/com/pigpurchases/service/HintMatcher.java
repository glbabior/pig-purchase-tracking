package com.pigpurchases.service;

import com.pigpurchases.model.BudgetEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic first pass of transaction categorization: match a transaction's
 * description/vendor against the budget entries, using each entry's name plus
 * any explicit patterns in its hints.
 *
 * <p><b>Normalization.</b> Both sides are lowercased and stripped of everything
 * that isn't a letter or digit before comparing, because statement text is full
 * of punctuation and spacing that the budget entry name doesn't have:
 * <pre>
 *   "City Power"  -> "citypower"   matches "CITYPOWER 800-555-0142 CA"
 *   "Novacell"     -> "novacell"    matches "NOVA-CELL PCS SVC"
 *   "Daily Grind"    -> "dailygrind"    matches "DAILYGRIND*COFFEE"
 * </pre>
 *
 * <p><b>Hints.</b> Hints are primarily prose written for the AI pass to read
 * ("This is my auto loan payment"), which cannot be substring-matched. A hint
 * line may therefore declare an explicit literal with a {@code match:} prefix,
 * and only those lines take part in this pass:
 * <pre>
 *   This is my auto loan payment
 *   match: MERIDIAN MOTORS
 *   match: CRESTLINE AUTO
 * </pre>
 *
 * <p><b>Composite hints.</b> A single {@code match:} line may require several
 * substrings at once by joining them with {@code +}; the transaction matches only
 * when it contains all of them. This lets a short-but-ambiguous token be paired
 * with a distinctive one, and lets two entries sharing a prefix be told apart:
 * <pre>
 *   match: MP + MAILORDER   matches "MP RX00042 MAILORDER80 800-555-0175 CA"
 *   match: HPK + monthly    matches only the HPK line that is also a monthly payment
 * </pre>
 * A part inside a composite may be as short as {@link #MIN_COMPOSITE_PART_LENGTH}
 * characters, since the AND of the parts is what makes it specific.
 *
 * <p><b>Safety.</b> Two rules keep this pass conservative, because a wrong
 * automatic answer is worse than parking a transaction for review:
 * <ul>
 *   <li>Patterns shorter than {@link #MIN_PATTERN_LENGTH} normalized characters
 *       are ignored. The entry "Gas" would otherwise match "CITYPOWER" and
 *       "POWELL ST GARAGE".</li>
 *   <li>When several entries match, the longest pattern wins as the most
 *       specific ("Harbor Park Pass" over "Harbor Park"). If the longest is a tie
 *       between different entries the transaction is left unmatched rather than
 *       guessed at.</li>
 * </ul>
 */
public class HintMatcher {

    /** Below this many normalized characters an entry-name pattern matches too much to trust. */
    static final int MIN_PATTERN_LENGTH = 4;
    /** Explicit hints are deliberate, so a shorter part is allowed (e.g. "HPK"). */
    static final int MIN_HINT_PART_LENGTH = 3;
    /**
     * Inside a composite (two or more '+'-joined parts) a shorter discriminator is
     * safe, because it is the AND of all parts that provides the specificity — e.g.
     * "KP" alone matches too much, but "MP + MAILORDER" does not.
     */
    static final int MIN_COMPOSITE_PART_LENGTH = 2;

    private static final String EXPLICIT_HINT_PREFIX = "match:";

    /**
     * One rule pointing at an entry. A rule can require several substrings, all of
     * which must appear (an AND, written with '+' in a hint: {@code hpk + monthly}).
     * {@code weight} is the combined specificity used to pick the best match.
     */
    public record Pattern(BudgetEntry entry, List<String> parts, int weight, String display) {
        boolean matches(String haystack) {
            if (parts.isEmpty()) {
                return false;
            }
            for (String part : parts) {
                if (!haystack.contains(part)) {
                    return false;
                }
            }
            return true;
        }
    }

    private final List<Pattern> patterns = new ArrayList<>();

    public HintMatcher(List<BudgetEntry> entries) {
        for (BudgetEntry entry : entries) {
            addName(entry, entry.getName());
            for (String hint : explicitHints(entry.getHints())) {
                addHint(entry, hint);
            }
        }
    }

    /** The entry name as a single-substring pattern (kept at the stricter min length). */
    private void addName(BudgetEntry entry, String name) {
        String normalized = normalize(name);
        if (normalized.length() >= MIN_PATTERN_LENGTH) {
            patterns.add(new Pattern(entry, List.of(normalized), normalized.length(),
                    name == null ? "" : name.trim()));
        }
    }

    /** An explicit hint, split on '+' into substrings that must ALL appear. */
    private void addHint(BudgetEntry entry, String raw) {
        String[] pieces = raw.split("\\+");
        int minPart = pieces.length > 1 ? MIN_COMPOSITE_PART_LENGTH : MIN_HINT_PART_LENGTH;

        List<String> parts = new ArrayList<>();
        int weight = 0;
        for (String piece : pieces) {
            String normalized = normalize(piece);
            if (normalized.length() < minPart) {
                // Drop the WHOLE hint, not just the offending part.
                //
                // Dropping parts individually turned an AND the user wrote into a bare
                // substring: "match: T + MOBILE" lost the "t" and registered as `mobile`,
                // which matches "MOBILE DEPOSIT" and "UNITED MOBILE INC"; "match: 7 + ELEVEN"
                // became `eleven` and matched "CORNER BISTRO PARK". The transaction then
                // landed in the wrong category, and the review screen showed the reason as
                // the full line the user typed, hiding that only one part was required.
                //
                // Parking is the safe failure here: a rule the app cannot honour as written
                // should produce no rule at all, because a wrong automatic answer is worse
                // than a transaction held for review.
                return;
            }
            parts.add(normalized);
            weight += normalized.length();
        }
        if (!parts.isEmpty()) {
            patterns.add(new Pattern(entry, parts, weight, raw.trim()));
        }
    }

    /** The {@code match:}-prefixed lines of a hints field; prose lines are left for the AI pass. */
    static List<String> explicitHints(String hints) {
        List<String> result = new ArrayList<>();
        if (hints == null || hints.isBlank()) {
            return result;
        }
        for (String line : hints.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.toLowerCase(Locale.ROOT).startsWith(EXPLICIT_HINT_PREFIX)) {
                String literal = trimmed.substring(EXPLICIT_HINT_PREFIX.length()).trim();
                if (!literal.isEmpty()) {
                    result.add(literal);
                }
            }
        }
        return result;
    }

    /** Lowercase and drop everything that isn't a letter or digit. */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    /** A confident match, carrying the pattern that produced it for display during review. */
    public record Match(BudgetEntry entry, String matchedOn) {}

    /**
     * The single best entry for this transaction, or empty when nothing matched
     * or the best match was ambiguous.
     */
    public java.util.Optional<Match> match(String description, String vendor) {
        String haystack = normalize(description) + "\0" + normalize(vendor);

        Pattern best = null;
        boolean ambiguous = false;
        for (Pattern p : patterns) {
            if (!p.matches(haystack)) {
                continue;
            }
            if (best == null || p.weight() > best.weight()) {
                best = p;
                ambiguous = false;
            } else if (p.weight() == best.weight()
                    && !p.entry().getId().equals(best.entry().getId())) {
                ambiguous = true; // equally specific, different entries -> don't guess
            }
        }
        return (best == null || ambiguous)
                ? java.util.Optional.empty()
                : java.util.Optional.of(new Match(best.entry(), best.display()));
    }

    /**
     * EVERY pattern that matches, not just the winner — the raw material for conflict
     * detection.
     *
     * <p>{@link #match} deliberately collapses this to one answer, which hides the two
     * cases worth knowing about. When two entries tie on weight the transaction is parked
     * with no explanation, and when they do not tie the heavier one wins silently — so a
     * hint can be permanently neutralised by one on another category and nothing says so.
     * Neither is visible from a screen that only ever shows the outcome.
     */
    public List<Pattern> allMatches(String description, String vendor) {
        String haystack = normalize(description) + "\0" + normalize(vendor);
        List<Pattern> hits = new ArrayList<>();
        for (Pattern p : patterns) {
            if (p.matches(haystack)) {
                hits.add(p);
            }
        }
        return hits;
    }

    /** Why a {@code match:} line is unusable, or null when it is fine. */
    public record HintProblem(String hint, String problem) {}

    /**
     * Check a {@code match:} line against the same rules {@link #addHint} applies, so the
     * user can be told <i>why</i> a hint does nothing.
     *
     * <p>{@code addHint} discards an unusable hint silently — deliberately, because a rule
     * the app cannot honour as written should produce no rule at all rather than a broader
     * one. But silence means a hint that looks present in the entry does nothing, with no
     * way to find out. This is the same logic, returning the reason instead of dropping it.
     */
    public static HintProblem validate(String rawHint) {
        String raw = rawHint == null ? "" : rawHint.trim();
        if (raw.isEmpty()) {
            return new HintProblem(raw, "empty");
        }
        String[] pieces = raw.split("\\+");
        int minPart = pieces.length > 1 ? MIN_COMPOSITE_PART_LENGTH : MIN_HINT_PART_LENGTH;
        for (String piece : pieces) {
            String normalized = normalize(piece);
            if (normalized.length() < minPart) {
                String shown = piece.trim().isEmpty() ? "(blank)" : piece.trim();
                return new HintProblem(raw, pieces.length > 1
                        ? "the part \"" + shown + "\" has fewer than " + MIN_COMPOSITE_PART_LENGTH
                          + " letters or digits, so the whole rule is ignored"
                        : "fewer than " + MIN_HINT_PART_LENGTH + " letters or digits, so it would"
                          + " match too much to trust");
            }
        }
        return null;
    }

    /** A throwaway matcher for one candidate rule, used to preview what it would catch. */
    public static HintMatcher forSingleHint(BudgetEntry entry, String rawHint) {
        HintMatcher m = new HintMatcher(List.of());
        m.addHint(entry, rawHint);
        return m;
    }

}
