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

    /** Below this many normalized characters a pattern matches too much to trust. */
    static final int MIN_PATTERN_LENGTH = 4;

    private static final String EXPLICIT_HINT_PREFIX = "match:";

    /** One thing to look for, and the entry it points at. */
    record Pattern(BudgetEntry entry, String normalized, String display) {}

    private final List<Pattern> patterns = new ArrayList<>();

    public HintMatcher(List<BudgetEntry> entries) {
        for (BudgetEntry entry : entries) {
            add(entry, entry.getName());
            for (String hint : explicitHints(entry.getHints())) {
                add(entry, hint);
            }
        }
    }

    private void add(BudgetEntry entry, String raw) {
        String normalized = normalize(raw);
        if (normalized.length() >= MIN_PATTERN_LENGTH) {
            patterns.add(new Pattern(entry, normalized, raw.trim()));
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
        String haystack = normalize(description) + "" + normalize(vendor);

        Pattern best = null;
        boolean ambiguous = false;
        for (Pattern p : patterns) {
            if (!haystack.contains(p.normalized())) {
                continue;
            }
            if (best == null || p.normalized().length() > best.normalized().length()) {
                best = p;
                ambiguous = false;
            } else if (p.normalized().length() == best.normalized().length()
                    && !p.entry().getId().equals(best.entry().getId())) {
                ambiguous = true; // equally specific, different entries -> don't guess
            }
        }
        return (best == null || ambiguous)
                ? java.util.Optional.empty()
                : java.util.Optional.of(new Match(best.entry(), best.display()));
    }
}
