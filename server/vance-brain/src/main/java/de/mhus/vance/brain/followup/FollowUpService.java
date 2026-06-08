package de.mhus.vance.brain.followup;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.mhus.vance.api.followup.FollowUpSuggestionDto;
import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.shared.metric.MetricService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Backend of the follow-up REST endpoint.
 *
 * <p>Two structural modes, selected by the {@code cursor} parameter:
 *
 * <ul>
 *   <li><b>Edit mode</b> ({@code cursor != null}) — splits the text at
 *       the cursor offset into {@code textBefore}/{@code textAfter}
 *       and asks the LLM what could come next at the cursor.</li>
 *   <li><b>Reply mode</b> ({@code cursor == null}) — passes the whole
 *       text as {@code precedingContext} and asks the LLM how to
 *       react to it (e.g. a follow-up question to an assistant's
 *       last message).</li>
 * </ul>
 *
 * <p>Either way the call goes through the {@code follow-up} recipe
 * via {@link LightLlmService}; the recipe's Pebble template branches
 * on which variable set is present. Empty result (zero suggestions)
 * is a valid outcome — the caller will see an empty list and
 * {@code HTTP 200}.
 *
 * <p>{@code count} is clamped to {@link #MAX_COUNT} server-side so a
 * misbehaving caller can't ask for arbitrarily many. The returned
 * list is additionally truncated to the (clamped) caller-supplied
 * count in case the LLM ignores the limit in the prompt.
 *
 * <p><b>Caching.</b> An in-memory Caffeine cache (LRU, size-bounded)
 * skips the LLM call when the same {@code (tenant, project, text,
 * cursor, count, mode)} tuple repeats — e.g. on page reload, multiple
 * browser tabs, or shared hubs. Keys are SHA-256 hex digests so the
 * map stays compact; values are the parsed
 * {@code List<FollowUpSuggestionDto>}. Multi-pod deployments lose
 * cross-pod sharing (each pod has its own cache); persistent caching
 * is a v2 concern. Cache hits / misses are counted under
 * {@code vance.followup.cache} with an {@code outcome} tag.
 */
@Service
@Slf4j
public class FollowUpService {

    /**
     * Recipe used as the LightLlm config profile. Tenants can
     * override by placing their own {@code recipes/follow-up.yaml}
     * in the document cascade — internal marker is preserved.
     */
    static final String DEFAULT_RECIPE_NAME = "follow-up";

    /** Hard server-side cap on suggestion count. */
    static final int MAX_COUNT = 10;

    /** Maximum number of cached suggestion results held at once. LRU
     *  evicts the oldest beyond this. */
    static final int CACHE_MAX_SIZE = 500;

    /** Time-to-live for cached suggestion results. Long enough that
     *  page reloads and multi-tab navigation reuse the entry; short
     *  enough that recipe/setting changes propagate naturally. */
    static final Duration CACHE_TTL = Duration.ofMinutes(30);

    /**
     * JsonSchemaLight description of the expected reply shape. Kept
     * permissive — the recipe prompt does the heavy lifting; we
     * only need the LLM to come back with a parseable JSON object.
     */
    static final Map<String, Object> FOLLOWUP_SCHEMA = Map.of(
            "type", "object");

    private final LightLlmService lightLlm;
    private final MetricService metrics;
    private final Cache<String, List<FollowUpSuggestionDto>> cache;

    public FollowUpService(LightLlmService lightLlm, MetricService metrics) {
        this.lightLlm = lightLlm;
        this.metrics = metrics;
        this.cache = Caffeine.newBuilder()
                .maximumSize(CACHE_MAX_SIZE)
                .expireAfterWrite(CACHE_TTL)
                .build();
    }

    /**
     * Produce follow-up suggestions for the given input.
     *
     * @param text     full input text — what the user is editing (edit
     *                 mode) or the preceding context to react to
     *                 (reply mode). Non-null, may be empty.
     * @param cursor   character offset from start (<b>edit mode</b>);
     *                 clamped into {@code [0, text.length()]}.
     *                 {@code null} switches to <b>reply mode</b>.
     * @param count    desired maximum number of suggestions; clamped
     *                 into {@code [1, MAX_COUNT]}
     * @param mode     optional UI-surface hint (e.g.
     *                 {@code "chat-prompt"}, {@code "chat-reply"});
     *                 passed through to the prompt for future
     *                 specialisation. Orthogonal to the edit/reply
     *                 branch.
     * @param tenantId tenant scope for setting cascades + API keys
     * @param projectId project scope; {@code _tenant} is the
     *                 conventional "no project context" default
     * @return suggestions list (never {@code null}; possibly empty)
     */
    public List<FollowUpSuggestionDto> suggest(
            String text,
            @Nullable Integer cursor,
            int count,
            @Nullable String mode,
            String tenantId,
            @Nullable String projectId) {

        if (text == null) {
            throw new IllegalArgumentException("text is required");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }

        int safeCount = Math.max(1, Math.min(count, MAX_COUNT));
        int safeCursor = cursor == null
                ? -1
                : Math.max(0, Math.min(cursor, text.length()));

        String cacheKey = buildCacheKey(
                tenantId, projectId, text, safeCursor, safeCount, mode);
        List<FollowUpSuggestionDto> cached = cache.getIfPresent(cacheKey);
        if (cached != null) {
            metrics.counter("vance.followup.cache", "outcome", "hit").increment();
            return cached;
        }
        metrics.counter("vance.followup.cache", "outcome", "miss").increment();

        Map<String, Object> pebbleVars = new LinkedHashMap<>();
        pebbleVars.put("count", safeCount);
        if (mode != null && !mode.isBlank()) {
            pebbleVars.put("mode", mode);
        }
        if (safeCursor >= 0) {
            // Edit mode: split text at cursor.
            pebbleVars.put("textBefore", text.substring(0, safeCursor));
            pebbleVars.put("textAfter", text.substring(safeCursor));
        } else {
            // Reply mode: whole text is the preceding context.
            pebbleVars.put("precedingContext", text);
        }

        Map<String, Object> raw = lightLlm.callForJson(LightLlmRequest.builder()
                .recipeName(DEFAULT_RECIPE_NAME)
                .userPrompt("Generate follow-up suggestions.")
                .pebbleVars(pebbleVars)
                .schema(FOLLOWUP_SCHEMA)
                .tenantId(tenantId)
                .projectId(projectId)
                .build());

        List<FollowUpSuggestionDto> parsed = parseSuggestions(raw, safeCount);
        cache.put(cacheKey, parsed);
        return parsed;
    }

    /**
     * SHA-256 hex digest of all inputs that influence the LLM call.
     * Keeps the map keys compact and avoids leaking user content into
     * heap-dumpable string interns. Reply mode passes {@code -1} for
     * cursor so edit/reply variants of otherwise-identical inputs
     * stay distinct in the cache.
     */
    private static String buildCacheKey(
            String tenantId,
            @Nullable String projectId,
            String text,
            int safeCursor,
            int safeCount,
            @Nullable String mode) {
        String payload = String.join("\0",
                tenantId,
                projectId == null ? "" : projectId,
                Integer.toString(safeCursor),
                Integer.toString(safeCount),
                mode == null ? "" : mode,
                text);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by every standard JRE — this is the
            // "should never happen" branch. Fall through to the raw
            // payload so the cache stays functional; it just won't
            // compact long inputs.
            return payload;
        }
    }

    /**
     * Parse the LLM's JSON reply into typed DTOs. Tolerant of the
     * common drift modes: missing {@code suggestions} key, items
     * shipped as bare strings instead of objects, extra fields.
     * Anything that doesn't yield a non-blank {@code text} is
     * dropped. The result is truncated to {@code limit}.
     */
    private static List<FollowUpSuggestionDto> parseSuggestions(
            Map<String, Object> raw, int limit) {
        Object listObj = raw.get("suggestions");
        if (!(listObj instanceof List<?> rawList)) {
            log.debug("FollowUpService: reply missing 'suggestions' array, returning empty");
            return List.of();
        }
        List<FollowUpSuggestionDto> out = new ArrayList<>(Math.min(rawList.size(), limit));
        for (Object item : rawList) {
            if (out.size() >= limit) break;
            FollowUpSuggestionDto dto = toSuggestion(item);
            if (dto != null) {
                out.add(dto);
            }
        }
        return out;
    }

    /**
     * Coerce one raw element from the LLM's array into a
     * {@link FollowUpSuggestionDto}. Accepts both
     * {@code {"text": "...", "kind": "..."}} objects and bare
     * strings (for which {@code kind} stays {@code null}). Returns
     * {@code null} for unusable input.
     */
    private static @Nullable FollowUpSuggestionDto toSuggestion(@Nullable Object item) {
        if (item instanceof String s) {
            String trimmed = s.trim();
            return trimmed.isEmpty()
                    ? null
                    : FollowUpSuggestionDto.builder().text(trimmed).build();
        }
        if (item instanceof Map<?, ?> map) {
            Object textObj = map.get("text");
            if (!(textObj instanceof String text)) return null;
            String trimmed = text.trim();
            if (trimmed.isEmpty()) return null;
            Object kindObj = map.get("kind");
            String kind = (kindObj instanceof String k && !k.isBlank()) ? k : null;
            return FollowUpSuggestionDto.builder()
                    .text(trimmed)
                    .kind(kind)
                    .build();
        }
        return null;
    }
}
