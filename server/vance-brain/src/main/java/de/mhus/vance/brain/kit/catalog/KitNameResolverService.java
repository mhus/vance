package de.mhus.vance.brain.kit.catalog;

import de.mhus.vance.api.kit.ProjectKitEntry;
import de.mhus.vance.api.kit.ProjectKitsCatalogDto;
import de.mhus.vance.brain.ai.light.LightLlmException;
import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.brain.ai.light.SchemaValidationException;
import de.mhus.vance.shared.kit.catalog.ProjectKitsCatalogService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Maps a free-text "kit wish" (the string Eddie or the user typed,
 * e.g. {@code "Schul-Aufsatz"} or {@code "essay kit"}) to a canonical
 * {@link ProjectKitEntry#getName()} in the tenant's project-kits
 * catalog. Strict catalog lookup happens upstream in
 * {@link ProjectKitInstaller}; this service only runs when the strict
 * pass missed and the caller wants a semantic second chance.
 *
 * <p>Single-shot {@link LightLlmService} call via the bundled
 * {@code kit-resolver} recipe (config profile, {@code internal: true}).
 * Output is a closed-vocabulary decision —
 * {@code {decision: MATCH|NONE, kitName: <catalog-name>|null, rationale}}.
 * Tenants override the recipe (cascade {@code recipes/kit-resolver.yaml})
 * to change the matching model, tighten heuristics, or relax the schema.
 * The Java side never has to change for tenant customisation.
 *
 * <p>Cost per resolution: one Gemini-flash-class call (~$0.0001, ~1s
 * with a warm cache). Cheap enough to run on every miss; the
 * {@code project_create} round-trip already costs ~10× more than that.
 *
 * <p>Failure mode: when the LightLlm call fails (schema budget
 * exhausted, provider error, recipe missing) the service returns
 * {@link Result#none} with a synthetic rationale rather than throwing.
 * The caller's downstream "unknown kit" error path is more informative
 * for the user than a stack trace.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KitNameResolverService {

    /** Recipe name resolved out of the bundled cascade. */
    public static final String RECIPE_NAME = "kit-resolver";

    /** Reply field names — kept as constants so tests can pin them. */
    static final String FIELD_DECISION = "decision";
    static final String FIELD_KIT_NAME = "kitName";
    static final String FIELD_RATIONALE = "rationale";

    /** Closed-vocabulary contract for the resolver reply. */
    static final Map<String, Object> RESOLVER_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    FIELD_DECISION, Map.of(
                            "type", "string",
                            "enum", List.of("MATCH", "NONE"),
                            "description", "MATCH when the wish maps "
                                    + "cleanly to one catalog entry; "
                                    + "NONE otherwise."),
                    // kitName intentionally has no `type` constraint:
                    // the JsonSchemaLight dialect doesn't support
                    // string-or-null unions, and the post-call catalog
                    // cross-check is the real source of truth anyway.
                    FIELD_KIT_NAME, Map.of(
                            "description", "Catalog `name` verbatim "
                                    + "when MATCH; null when NONE."),
                    FIELD_RATIONALE, Map.of(
                            "type", "string",
                            "description", "1-2 sentences explaining "
                                    + "the choice. Shown to the user "
                                    + "on NONE.")),
            "required", List.of(FIELD_DECISION, FIELD_RATIONALE));

    private final LightLlmService lightLlm;
    private final ProjectKitsCatalogService catalogService;

    /**
     * Resolve {@code wish} against the tenant catalog via the
     * {@code kit-resolver} recipe. Returns the chosen catalog name
     * (cross-checked against the catalog after the LLM call — never a
     * hallucinated string) on MATCH, or {@link Result#none} on NONE /
     * LightLlm failure / any other recovery path. The companion
     * {@link Result#rationale} on the return value is the resolver's
     * reasoning string — caller surfaces it to the user on NONE.
     *
     * <p>Best-effort: a LightLlm failure (schema budget exhausted,
     * provider error, recipe missing) logs WARN and returns NONE with
     * a synthetic rationale rather than throwing. The caller's
     * downstream "unknown kit" error path is more informative for the
     * user than a stack trace.
     */
    public Result resolve(String tenantId, String projectId, String wish) {
        if (tenantId == null || tenantId.isBlank()) {
            return Result.none("tenantId missing — no resolution possible");
        }
        if (projectId == null || projectId.isBlank()) {
            return Result.none("projectId missing — no resolution possible");
        }
        if (wish == null || wish.isBlank()) {
            return Result.none("empty wish — nothing to resolve");
        }

        ProjectKitsCatalogDto catalog = catalogService.load(tenantId);
        List<ProjectKitEntry> entries = catalog == null || catalog.getKits() == null
                ? List.of() : catalog.getKits();
        if (entries.isEmpty()) {
            return Result.none(
                    "tenant catalog is empty — nothing to match '" + wish + "' against");
        }

        Map<String, Object> raw;
        try {
            raw = lightLlm.callForJson(LightLlmRequest.builder()
                    .recipeName(RECIPE_NAME)
                    .userPrompt(wish)
                    .pebbleVars(Map.of(
                            "wish", wish,
                            "entries", renderEntries(entries)))
                    .schema(RESOLVER_SCHEMA)
                    .tenantId(tenantId)
                    .projectId(projectId)
                    .build());
        } catch (SchemaValidationException e) {
            log.warn("KitNameResolver: resolver schema budget exhausted tenant='{}' "
                            + "project='{}' wish='{}' attempts={}: {}",
                    tenantId, projectId, wish, e.getAttempts(), e.getMessage());
            return Result.none("resolver could not produce a valid reply within "
                    + e.getAttempts() + " attempts");
        } catch (LightLlmException e) {
            log.warn("KitNameResolver: resolver call failed tenant='{}' project='{}' wish='{}': {}",
                    tenantId, projectId, wish, e.toString());
            return Result.none("resolver call failed: " + e.getMessage());
        }

        ParsedReply parsed = parseReply(raw);
        if (parsed == null) {
            return Result.none("resolver returned an unparseable reply");
        }

        if ("MATCH".equals(parsed.decision())
                && parsed.kitName != null && !parsed.kitName.isBlank()) {
            // Closed-vocab guard: cross-check the LLM pick against the
            // catalog. Catches the rare case where the model returns a
            // plausible-sounding name that doesn't exist (matching the
            // wish description but missing from the catalog list).
            ProjectKitEntry verified = catalogService.findByName(tenantId, parsed.kitName);
            if (verified != null) {
                log.info("KitNameResolver: wish='{}' → kit='{}' (rationale: {})",
                        wish, verified.getName(), abbrev(parsed.rationale, 120));
                return Result.match(verified.getName(), parsed.rationale);
            }
            log.warn("KitNameResolver: model picked '{}' but catalog has no such entry"
                            + " (wish='{}', rationale='{}')",
                    parsed.kitName, wish, abbrev(parsed.rationale, 120));
            return Result.none(
                    "resolver picked '" + parsed.kitName
                            + "', but that name is not in the catalog");
        }

        log.info("KitNameResolver: wish='{}' → NONE (rationale: {})",
                wish, abbrev(parsed.rationale, 120));
        return Result.none(parsed.rationale == null || parsed.rationale.isBlank()
                ? "resolver returned NONE without rationale"
                : parsed.rationale);
    }

    // ──────────────────── Reply parsing ────────────────────

    static @Nullable ParsedReply parseReply(Map<String, Object> raw) {
        if (raw == null) return null;
        Object decision = raw.get(FIELD_DECISION);
        if (!(decision instanceof String d) || d.isBlank()) return null;
        Object kitNameRaw = raw.get(FIELD_KIT_NAME);
        String kitName = kitNameRaw instanceof String s ? nullIfBlank(s) : null;
        Object rationaleRaw = raw.get(FIELD_RATIONALE);
        String rationale = rationaleRaw instanceof String r ? r : "";
        return new ParsedReply(d, kitName, rationale);
    }

    // ──────────────────── Pebble entry rendering ────────────────────

    /**
     * Flatten the catalog entries to plain maps so the Pebble
     * template can walk them with {@code {% for e in entries %}}.
     * Stable order (catalog order) keeps the prompt cache warm
     * across resolutions over the same tenant snapshot.
     */
    static List<Map<String, String>> renderEntries(List<ProjectKitEntry> entries) {
        List<Map<String, String>> out = new ArrayList<>(entries.size());
        for (ProjectKitEntry e : entries) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("name", e.getName());
            if (e.getTitle() != null && !e.getTitle().isBlank()) {
                m.put("title", e.getTitle());
            }
            if (e.getDescription() != null && !e.getDescription().isBlank()) {
                m.put("description", e.getDescription());
            }
            out.add(m);
        }
        return out;
    }

    private static @Nullable String nullIfBlank(@Nullable String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty() || "null".equalsIgnoreCase(t)) return null;
        return t;
    }

    private static String abbrev(String s, int max) {
        if (s == null) return "";
        String trimmed = s.trim();
        if (trimmed.length() <= max) return trimmed;
        return trimmed.substring(0, max - 3) + "...";
    }

    /** Resolver outcome — match with the picked name + rationale, or none + rationale. */
    public record Result(boolean matched, @Nullable String kitName, String rationale) {
        public static Result match(String kitName, String rationale) {
            return new Result(true, kitName, rationale);
        }
        public static Result none(String rationale) {
            return new Result(false, null, rationale);
        }
        public Optional<String> asOptional() {
            return matched ? Optional.ofNullable(kitName) : Optional.empty();
        }
    }

    /** Internal — LightLlm reply parsed but not yet cross-checked against the catalog. */
    record ParsedReply(String decision, @Nullable String kitName, String rationale) {
        // Compact-canonical accessor so callers can branch on decision uppercase.
        @Override public String decision() {
            return decision == null ? "" : decision.toUpperCase(Locale.ROOT);
        }
    }
}
