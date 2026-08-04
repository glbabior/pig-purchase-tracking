package com.pigpurchases.parser;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Where the {@code *ValidationTest}s find the real statements they reconcile against.
 *
 * <p>Those statements are personal financial data and are never committed — but until
 * this class existed, the <b>paths to them</b> were, hard-coded into four test files.
 * That published the machine's user name, the folder layout, which institutions the
 * accounts are with, their nicknames, and one statement's filename, which carried the
 * account holder's name. A repo can be shared; a home directory should not have to be.
 *
 * <p>So the locations live outside the repo now, resolved in this order:
 *
 * <ol>
 *   <li>System property {@code pigpurchases.statements.<key>} — for a one-off run:
 *       {@code mvnw test -Dpigpurchases.statements.crestline=D:/statements/crestline}</li>
 *   <li>Environment variable {@code PIGPURCHASES_STATEMENTS_<KEY>}, dots becoming
 *       underscores and the whole thing upper-cased.</li>
 *   <li>{@code statements.local.properties} in the project root — gitignored, and the
 *       normal way to set this up on a machine that has the statements. Copy
 *       {@code statements.local.properties.example} and fill in the paths.</li>
 * </ol>
 *
 * <p>Anything unset makes its test skip, which is the same behaviour a fresh clone has
 * always had. <b>That skip is the dangerous part</b>, and the reason the messages here
 * name the exact key that was missing: a validation test that quietly stops running
 * looks identical to one that passes, and the whole point of these tests is to catch a
 * parser that no longer reconciles. If you have the statements and see a skip, the
 * config is wrong — treat it as a failure, not as noise.
 *
 * <p><b>The key names are the caller's, not this class's.</b> Nothing here knows about
 * banks, card issuers or any particular institution — {@link #value} builds a property
 * and environment name from whatever string it is handed. The keys the shipped tests
 * happen to use describe the author's three statement formats and carry no privilege.
 * A parser you write gets a key you choose, and a clone that configures nothing at all
 * builds green with every validation test skipped. That is the intended resting state
 * for anyone but the author: the parsers and their tests are reference material for the
 * shape of the problem, not a set of formats this project requires you to possess.
 */
final class LocalStatements {

    /** Gitignored, project-root, optional. Absent on CI and on any fresh clone. */
    private static final Path CONFIG = Path.of("statements.local.properties");

    private static final Properties FILE_VALUES = load();

    private LocalStatements() {}

    private static Properties load() {
        Properties props = new Properties();
        if (!Files.isRegularFile(CONFIG)) {
            return props;
        }
        try (InputStream in = Files.newInputStream(CONFIG)) {
            props.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + CONFIG.toAbsolutePath(), e);
        }
        return props;
    }

    /** The configured value for a key, from whichever source supplies it first. */
    static Optional<String> value(String key) {
        String fromProperty = System.getProperty("pigpurchases.statements." + key);
        if (isSet(fromProperty)) {
            return Optional.of(fromProperty.trim());
        }
        String envKey = "PIGPURCHASES_STATEMENTS_"
                + key.replace('.', '_').toUpperCase(Locale.ROOT);
        String fromEnv = System.getenv(envKey);
        if (isSet(fromEnv)) {
            return Optional.of(fromEnv.trim());
        }
        String fromFile = FILE_VALUES.getProperty(key);
        return isSet(fromFile) ? Optional.of(fromFile.trim()) : Optional.empty();
    }

    /**
     * A configured folder, or empty when the key was never set.
     *
     * <p><b>Configuring a key is a statement of intent</b> — "run these tests, the data is
     * here" — so a path that does not resolve is a typo, a moved folder or a drive that
     * changed letter, and it throws. It used to return empty, which made it indistinguishable
     * from the unconfigured case and skipped the test in silence. Surefire does not print
     * assumption messages, so that silence was total: {@code Skipped: 1} and no reason.
     *
     * <p>That matters more here than it would elsewhere. These tests are the only thing
     * standing behind the parsers — the README says a green CI run does not prove the
     * parsers still reconcile — so a validation test that quietly stops running removes the
     * safeguard without removing the appearance of it. Paths rot: a folder named for a year
     * stops being right in January.
     *
     * <p>An unset key still returns empty, and the caller still skips. Nobody asked for the
     * test, so there is nothing to complain about.
     *
     * <p><b>The complaint is always answerable two ways</b>, and withdrawing the request is
     * as legitimate as fixing it. If the folder moved, correct the path. If it is gone for
     * good and you do not want it checked any more, blank the key or comment it out — a key
     * with no value counts as unset, so the test goes back to skipping and stays quiet. This
     * is deliberately not a demand that the data exist forever; it is a demand that the
     * config and the disk agree, which you can satisfy from either side.
     */
    static Optional<Path> dir(String key) {
        Optional<String> configured = value(key);
        if (configured.isEmpty()) {
            return Optional.empty();
        }
        Path path = Path.of(configured.get());
        if (!Files.isDirectory(path)) {
            throw new IllegalStateException(
                    "Statements folder for \"" + key + "\" does not exist: " + path
                    + " - either fix the path in " + CONFIG + ", or, if it is gone for good,"
                    + " blank the key (\"" + key + "=\") or comment it out to stop checking"
                    + " it. An empty key counts as unset and simply skips.");
        }
        return Optional.of(path);
    }

    /** A configured decimal (an expected total for a known sample), or empty. */
    static Optional<BigDecimal> decimal(String key) {
        return value(key).map(BigDecimal::new);
    }

    /** A configured whole number (an expected transaction count), or empty. */
    static Optional<Integer> integer(String key) {
        return value(key).map(Integer::parseInt);
    }

    /**
     * A configured comma-separated list, or empty when unset. Blank entries are dropped, so
     * a trailing comma is harmless; entries are trimmed, so "Sewer, Trash" works as written.
     */
    static List<String> strings(String key) {
        return value(key)
                .map(v -> Arrays.stream(v.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toList()))
                .orElse(List.of());
    }

    /**
     * Enforce that a group of related keys is either fully configured or fully absent.
     *
     * <p>Half a sample is the shape of problem this whole file exists to prevent: it reads as
     * configured, runs, and quietly checks less than you think. Naming a reference statement
     * but not what it should contain gives the appearance of a reference check without the
     * substance, and nothing on screen distinguishes it from the real thing.
     *
     * @return the message describing the inconsistency, or null when the group is consistent
     */
    static String incompleteGroup(String... keys) {
        List<String> set = new ArrayList<>();
        List<String> unset = new ArrayList<>();
        for (String key : keys) {
            (value(key).isPresent() ? set : unset).add(key);
        }
        if (set.isEmpty() || unset.isEmpty()) {
            return null;
        }
        return "these keys belong together and must be set as a group or left out entirely -"
                + " set: " + String.join(", ", set)
                + " / missing: " + String.join(", ", unset);
    }

    /**
     * Why a test is skipping, naming the key so the fix is obvious. Deliberately
     * mentions the file rather than only the key: someone who has never set this up
     * needs to be told the file exists at all.
     */
    static String unset(String key) {
        // ASCII only, deliberately. These strings are read in a Windows console, whose
        // codepage turns an em-dash into a replacement character mid-sentence.
        return "No statements configured for \"" + key + "\" - set it in "
                + CONFIG + " (copy " + CONFIG + ".example) or pass"
                + " -Dpigpurchases.statements." + key + "=... . Skipping.";
    }

    /**
     * Why a test found no PDFs, told apart correctly.
     *
     * <p>A folder that resolves but holds no statements is <b>not</b> an error: a year you
     * have not downloaded yet is a real and unalarming state, and {@link #dir} has already
     * proved the path itself is good. But it is not "unconfigured" either, and saying so
     * would send you to fix a config file that is already right.
     */
    static String noStatements(String... keys) {
        List<String> configured = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String key : keys) {
            (value(key).isPresent() ? configured : missing).add(key);
        }
        if (configured.isEmpty()) {
            return unset(String.join("\" / \"", missing));
        }
        return "The configured folder(s) for \"" + String.join("\", \"", configured)
                + "\" resolve but hold no PDFs. Nothing to reconcile; skipping.";
    }

    /** The PDFs directly inside a folder, sorted, or none when the folder is absent. */
    static List<Path> pdfsIn(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                    .sorted().collect(Collectors.toList());
        }
    }

    /** The PDFs for a configured key, or none when it isn't configured. */
    static List<Path> pdfsFor(String key) throws IOException {
        Optional<Path> dir = dir(key);
        return dir.isPresent() ? pdfsIn(dir.get()) : List.of();
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}
