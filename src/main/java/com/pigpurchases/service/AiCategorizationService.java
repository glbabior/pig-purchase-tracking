package com.pigpurchases.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pigpurchases.model.BudgetEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Categorizes the transactions {@link HintMatcher} could not resolve, by asking
 * Claude to map each one to a budget entry.
 *
 * <p><b>Privacy boundary.</b> Only the transaction description and vendor name
 * are sent, together with the budget entry names and their hints. Amounts,
 * balances, dates, account numbers, and the statement files themselves never
 * leave the machine — see the README's Data Privacy Summary. {@link #promptFor}
 * is the single place a request body is built, so that guarantee is checkable in
 * one function (and is asserted by AiCategorizationServiceTest).
 *
 * <p>The service degrades quietly: with no API credentials configured, or on any
 * API failure, it returns no suggestions and the affected transactions simply
 * stay parked as "Other" rather than failing the mapping run.
 */
@Service
public class AiCategorizationService {

    private static final Logger log = LoggerFactory.getLogger(AiCategorizationService.class);

    /** The debug-log category every outbound Claude call is filed under. */
    private static final String CATEGORY = "anthropic";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired private DebugLogService debugLog;

    @Value("${pigpurchases.ai.enabled:true}")
    private boolean enabled;

    @Value("${pigpurchases.ai.model:claude-haiku-4-5}")
    private String model;

    /** Transactions per request. Keeps any single response comfortably inside maxTokens. */
    @Value("${pigpurchases.ai.batch-size:40}")
    private int batchSize;

    @Value("${pigpurchases.ai.max-tokens:16000}")
    private long maxTokens;

    /** Built lazily so a missing API key costs nothing until categorization is actually used. */
    private AnthropicClient client;
    private boolean clientUnavailable;

    /** One distinct thing to categorize. Deliberately carries no amount. */
    public record Candidate(String key, String description, String vendor) {}

    /** null budgetEntryId means "leave it parked" — the model is told to say so rather than guess. */
    public record Suggestion(Long budgetEntryId, String reason) {}

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * True when categorization is configured and credentials appear to exist.
     *
     * <p>The SDK's {@code fromEnv()} does not validate anything — it builds a
     * client happily with no key and fails later with a 401 per request. So the
     * credential check is done here, and an authentication failure at request
     * time latches this off (see {@link #categorize}) rather than re-failing on
     * every batch of every subsequent run.
     */
    public synchronized boolean isAvailable() {
        return enabled && !clientUnavailable && hasCredentials();
    }

    /**
     * Whether the SDK has something to authenticate with: an API key or auth
     * token in the environment, or a stored {@code ant auth login} profile.
     */
    private static boolean hasCredentials() {
        if (isSet(System.getenv("ANTHROPIC_API_KEY")) || isSet(System.getenv("ANTHROPIC_AUTH_TOKEN"))) {
            return true;
        }
        String configDir = System.getenv("ANTHROPIC_CONFIG_DIR");
        if (!isSet(configDir)) {
            String appData = System.getenv("APPDATA"); // Windows
            configDir = isSet(appData)
                    ? appData + "/Anthropic"
                    : System.getProperty("user.home", "") + "/.config/anthropic";
        }
        return Files.isDirectory(Path.of(configDir, "credentials"));
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    private synchronized AnthropicClient clientOrNull() {
        if (client == null && !clientUnavailable) {
            // Resolves ANTHROPIC_API_KEY, ANTHROPIC_AUTH_TOKEN, or an `ant auth login` profile.
            client = AnthropicOkHttpClient.fromEnv();
        }
        return client;
    }

    /** An auth failure won't fix itself mid-run — stop trying until the app restarts. */
    private synchronized void markUnauthenticated(Exception ex) {
        if (!clientUnavailable) {
            clientUnavailable = true;
            log.warn("AI categorization disabled for this session — credentials were rejected ({}). "
                    + "Set ANTHROPIC_API_KEY and restart. Unresolved transactions stay parked as \"Other\".",
                    ex.getClass().getSimpleName());
            debugLog.error(CATEGORY, "Credentials rejected (" + apiErrorText(ex)
                    + "). AI categorization is off until the key is fixed and the app restarts.");
        }
    }

    /**
     * Suggest a budget entry for each candidate. Returns only confident answers —
     * a candidate absent from the result stays parked. Never throws: a failed
     * batch is logged and skipped so the mapping run still completes.
     */
    public Map<String, Suggestion> categorize(List<Candidate> candidates, List<BudgetEntry> entries) {
        Map<String, Suggestion> result = new LinkedHashMap<>();
        if (candidates.isEmpty() || entries.isEmpty()) {
            return result;
        }
        if (!isAvailable()) {
            debugLog.info(CATEGORY, "Skipped: AI categorization is "
                    + (enabled ? "unavailable (no credentials found)" : "disabled in settings")
                    + "; " + candidates.size() + " merchant(s) stay parked as \"Other\".");
            return result;
        }

        int batches = (candidates.size() + batchSize - 1) / batchSize;
        debugLog.info(CATEGORY, "Categorizing " + candidates.size() + " distinct merchant(s) in "
                + batches + " request(s) to model " + model + ".");

        int succeeded = 0;
        for (int start = 0; start < candidates.size(); start += batchSize) {
            List<Candidate> batch = candidates.subList(start, Math.min(start + batchSize, candidates.size()));
            try {
                Map<String, Suggestion> batchResult = categorizeBatch(batch, entries);
                result.putAll(batchResult);
                succeeded++;
            } catch (UnauthorizedException | PermissionDeniedException ex) {
                // Bad or missing credentials: every remaining batch would fail the
                // same way, so stop rather than firing one doomed request per batch.
                markUnauthenticated(ex);
                break;
            } catch (Exception ex) {
                // A failed batch means those transactions stay parked, which is the
                // same outcome as before the AI pass existed. Never fail the run.
                log.warn("AI categorization batch failed ({} items): {}", batch.size(), ex.toString());
                debugLog.error(CATEGORY, "Request failed for " + batch.size() + " merchant(s): "
                        + apiErrorText(ex));
            }
        }
        debugLog.info(CATEGORY, "Done: " + result.size() + " merchant(s) categorized across "
                + succeeded + "/" + batches + " successful request(s).");
        return result;
    }

    private Map<String, Suggestion> categorizeBatch(List<Candidate> batch, List<BudgetEntry> entries)
            throws Exception {
        // No `thinking` config: this is a simple classification, and adaptive
        // thinking is rejected on older models such as Haiku 4.5. Omitting it
        // works on every model and keeps the request cheap.
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .system(SYSTEM_PROMPT)
                .outputConfig(OutputConfig.builder().format(responseSchema()).build())
                .addUserMessage(promptFor(batch, entries))
                .build();

        long startedAt = System.nanoTime();
        Message message = clientOrNull().messages().create(params);
        long ms = (System.nanoTime() - startedAt) / 1_000_000;

        String json = firstText(message);
        Map<String, Suggestion> suggestions = parseSuggestions(json, batch, entries);
        debugLog.info(CATEGORY, "Request OK (" + batch.size() + " sent, " + suggestions.size()
                + " categorized, " + tokenUsage(message) + ", " + ms + " ms).");
        return suggestions;
    }

    /** Compact token line for the debug log, e.g. "1,842 in / 512 out tokens". */
    private static String tokenUsage(Message message) {
        try {
            var usage = message.usage();
            return usage.inputTokens() + " in / " + usage.outputTokens() + " out tokens";
        } catch (Exception ignored) {
            return "usage n/a";
        }
    }

    /** Prefer the API's own error message over the SDK exception's toString(). */
    private static String apiErrorText(Exception ex) {
        String text = ex.getMessage();
        return text != null && !text.isBlank() ? text : ex.toString();
    }

    private static String firstText(Message message) {
        for (ContentBlock block : message.content()) {
            if (block.isText()) {
                return block.asText().text();
            }
        }
        return "";
    }

    private static final String SYSTEM_PROMPT = """
            You map credit-card and bank transactions to a household's budget categories.

            You receive the budget categories (each with an id, a name, and optional \
            free-text hints written by the household) and a list of transaction \
            descriptions taken from statements. Assign each transaction to the single \
            best category.

            Rules:
            - Honor the hints. They are the household's own notes about what a category \
              covers and take precedence over your general knowledge of a merchant.
            - Statement text is abbreviated and noisy (e.g. "SQ *BREW HOUSE", \
              "TST* PIZZA NIGHT", trailing store numbers, city and state codes). \
              Identify the underlying merchant.
            - Use null for budgetEntryId when no category is a good fit, when the \
              merchant is unidentifiable, or when two categories fit equally well. \
              A wrong assignment is worse than leaving it uncategorized for the \
              household to review, so do not guess.
            - Keep each reason under 12 words: name the merchant and why it fits.
            """;

    /**
     * The request body. Descriptions and vendor names only — deliberately no
     * amounts, dates, account identifiers, or balances.
     */
    static String promptFor(List<Candidate> batch, List<BudgetEntry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("Budget categories:\n");
        for (BudgetEntry entry : entries) {
            sb.append("- id=").append(entry.getId()).append(" | ").append(entry.getName());
            String hints = entry.getHints();
            if (hints != null && !hints.isBlank()) {
                sb.append(" | hints: ").append(hints.replaceAll("\\s+", " ").trim());
            }
            sb.append('\n');
        }

        sb.append("\nTransactions to categorize:\n");
        for (int i = 0; i < batch.size(); i++) {
            Candidate candidate = batch.get(i);
            sb.append(i).append(". ").append(candidate.description());
            if (candidate.vendor() != null && !candidate.vendor().isBlank()
                    && !candidate.vendor().equals(candidate.description())) {
                sb.append("  (vendor: ").append(candidate.vendor()).append(")");
            }
            sb.append('\n');
        }
        sb.append("\nReturn one assignment per transaction, using the index shown above.");
        return sb.toString();
    }

    /** Constrains the reply so it never needs defensive parsing. */
    private static JsonOutputFormat responseSchema() {
        Map<String, Object> assignment = Map.of(
                "type", "object",
                "properties", Map.of(
                        "index", Map.of("type", "integer"),
                        "budgetEntryId", Map.of("type", List.of("integer", "null")),
                        "reason", Map.of("type", "string")),
                "required", List.of("index", "budgetEntryId", "reason"),
                "additionalProperties", false);

        JsonOutputFormat.Schema schema = JsonOutputFormat.Schema.builder()
                .putAdditionalProperty("type", JsonValue.from("object"))
                .putAdditionalProperty("properties", JsonValue.from(Map.of(
                        "assignments", Map.of("type", "array", "items", assignment))))
                .putAdditionalProperty("required", JsonValue.from(List.of("assignments")))
                .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                .build();

        return JsonOutputFormat.builder().schema(schema).build();
    }

    /** Map the model's index-keyed answers back onto candidates, dropping anything unusable. */
    private Map<String, Suggestion> parseSuggestions(String json, List<Candidate> batch,
                                                     List<BudgetEntry> entries) throws Exception {
        Map<Long, String> validEntries = new HashMap<>();
        for (BudgetEntry entry : entries) {
            validEntries.put(entry.getId(), entry.getName());
        }

        Map<String, Suggestion> result = new LinkedHashMap<>();
        JsonNode assignments = objectMapper.readTree(json).path("assignments");
        for (JsonNode node : assignments) {
            int index = node.path("index").asInt(-1);
            if (index < 0 || index >= batch.size()) {
                continue;
            }
            JsonNode idNode = node.path("budgetEntryId");
            if (idNode.isNull() || idNode.isMissingNode()) {
                continue; // model declined to categorize; stays parked
            }
            long entryId = idNode.asLong();
            if (!validEntries.containsKey(entryId)) {
                log.warn("AI returned unknown budget entry id {}; leaving transaction parked", entryId);
                continue;
            }
            String reason = node.path("reason").asText("");
            result.put(batch.get(index).key(), new Suggestion(entryId, reason));
        }
        return result;
    }

    /** Distinct candidates in a stable order, so repeated merchants cost one request slot each. */
    public static List<Candidate> dedupe(List<Candidate> candidates) {
        Map<String, Candidate> byKey = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            byKey.putIfAbsent(candidate.key(), candidate);
        }
        return new ArrayList<>(byKey.values());
    }
}
