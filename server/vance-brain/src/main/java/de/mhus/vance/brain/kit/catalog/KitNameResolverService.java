package de.mhus.vance.brain.kit.catalog;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.kit.ProjectKitEntry;
import de.mhus.vance.api.kit.ProjectKitsCatalogDto;
import de.mhus.vance.api.ws.Profiles;
import de.mhus.vance.brain.recipe.AppliedRecipe;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.kit.catalog.ProjectKitsCatalogService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Maps a free-text "kit wish" (the string Eddie or the user typed, e.g.
 * {@code "Schul-Aufsatz"} or {@code "essay kit"}) to a canonical
 * {@link ProjectKitEntry#getName()} in the tenant's project-kits
 * catalog. Strict catalog lookup happens upstream in
 * {@link ProjectKitInstaller}; this service only runs when the strict
 * pass missed and the caller wants a semantic second chance.
 *
 * <p>Single-shot Jeltz call via the bundled {@code kit-resolver} recipe.
 * Output is a closed-vocabulary decision —
 * {@code {decision: MATCH|NONE, kitName: <catalog-name>|null, rationale}}.
 * Tenants override the recipe (cascade
 * {@code recipes/kit-resolver.yaml}) to change the matching model,
 * tighten heuristics, or relax the schema. The Java side never has to
 * change for tenant customisation.
 *
 * <p>Cost per resolution: one Gemini-flash-class call (~$0.0001, ~1s
 * with a warm cache). Cheap enough to run on every miss; the
 * {@code project_create} round-trip already costs ~10× more than that.
 *
 * <p>Failure mode: when Jeltz returns NONE, or the resolver picks a
 * name that turns out to be absent from the catalog (LLM hallucination
 * past the closed vocab), the service returns
 * {@link Optional#empty()} — the caller throws its own typed
 * {@link de.mhus.vance.brain.kit.KitException} with the catalog list +
 * resolver rationale so the user/LLM sees an actionable error. We do
 * not surface raw Jeltz errors here — they would only confuse the
 * project-create user.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KitNameResolverService {

    /** Recipe name resolved out of the bundled cascade. */
    public static final String RECIPE_NAME = "kit-resolver";

    /** Display name of the per-project system session that owns all resolver runs. */
    public static final String SYSTEM_SESSION_NAME = "_system_kit_resolver";

    /** Jeltz engineParam keys (see {@code specification/jeltz-engine.md} §3.1). */
    private static final String PARAM_PROMPT = "prompt";
    private static final String PARAM_SCHEMA = "schema";

    /** Strict closed-vocabulary contract for the resolver reply. */
    private static final Map<String, Object> RESOLVER_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "decision", Map.of(
                            "type", "string",
                            "enum", List.of("MATCH", "NONE"),
                            "description", "MATCH when the wish maps "
                                    + "cleanly to one catalog entry; "
                                    + "NONE otherwise."),
                    "kitName", Map.of(
                            "type", List.of("string", "null"),
                            "description", "Catalog `name` verbatim "
                                    + "when MATCH; null when NONE."),
                    "rationale", Map.of(
                            "type", "string",
                            "description", "1-2 sentences explaining "
                                    + "the choice. Shown to the user "
                                    + "on NONE.")),
            "required", List.of("decision", "kitName", "rationale"));

    private final SessionService sessionService;
    private final ThinkProcessService thinkProcessService;
    private final ThinkEngineService thinkEngineService;
    private final RecipeResolver recipeResolver;
    private final LaneScheduler laneScheduler;
    private final ChatMessageService chatMessageService;
    private final ProjectKitsCatalogService catalogService;
    private final ObjectMapper jsonMapper;

    /**
     * Resolve {@code wish} against the tenant catalog via the Jeltz
     * recipe. Returns the chosen catalog name (validated against the
     * catalog after the LLM call — never a hallucinated string) on
     * MATCH, or {@link Optional#empty()} on NONE / Jeltz failure / any
     * other recovery path. The companion {@link Result#rationale} on
     * the return value is the resolver's reasoning string — caller
     * surfaces it to the user on NONE.
     *
     * <p>Best-effort: a Jeltz spawn failure (Mongo blip, model error)
     * logs WARN and returns NONE with a synthetic rationale rather
     * than throwing. The caller's downstream "unknown kit" error path
     * is more informative for the user than a stack trace.
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

        SessionDocument session = resolveSystemSession(tenantId, projectId);

        AppliedRecipe applied;
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put(PARAM_PROMPT, buildPrompt(wish, entries));
            params.put(PARAM_SCHEMA, RESOLVER_SCHEMA);
            applied = recipeResolver.apply(
                    tenantId, projectId, RECIPE_NAME,
                    /*connectionProfile*/ Profiles.SCHEDULER,
                    params);
        } catch (RuntimeException e) {
            log.warn("KitNameResolver: recipe apply failed tenant='{}' project='{}' wish='{}': {}",
                    tenantId, projectId, wish, e.toString());
            return Result.none("resolver recipe not available: " + e.getMessage());
        }

        ThinkProcessDocument child = createChildProcess(
                tenantId, projectId, session, applied, wish);

        try {
            driveSynchronously(child);
        } catch (RuntimeException e) {
            log.warn("KitNameResolver: jeltz drive failed tenant='{}' project='{}' wish='{}': {}",
                    tenantId, projectId, wish, e.toString());
            return Result.none("resolver call failed: " + e.getMessage());
        }

        ParsedReply parsed = readReply(tenantId, session.getSessionId(), child.getId());
        if (parsed == null) {
            return Result.none("resolver returned no parseable reply");
        }

        if ("MATCH".equalsIgnoreCase(parsed.decision)
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

    // ──────────────────── Spawn pipeline ────────────────────

    private SessionDocument resolveSystemSession(String tenantId, String projectId) {
        return sessionService.findSystemSession(
                        tenantId, projectId, SYSTEM_SESSION_NAME)
                .orElseGet(() -> createFreshSession(tenantId, projectId));
    }

    private SessionDocument createFreshSession(String tenantId, String projectId) {
        SessionDocument created = sessionService.create(
                tenantId,
                /*userId*/ "system",
                projectId,
                SYSTEM_SESSION_NAME,
                Profiles.SCHEDULER,
                /*clientVersion*/ "kit-resolver",
                /*clientName*/ null,
                /*system*/ true);
        sessionService.markBootstrapped(created.getSessionId());
        log.info("KitNameResolver: system-session created tenant='{}' project='{}' sessionId='{}'",
                tenantId, projectId, created.getSessionId());
        return created;
    }

    private ThinkProcessDocument createChildProcess(
            String tenantId, String projectId, SessionDocument session,
            AppliedRecipe applied, String wish) {
        String name = "kit-resolver-" + Instant.now().toEpochMilli();
        return thinkProcessService.create(
                tenantId,
                projectId,
                session.getSessionId(),
                name,
                applied.engine(),
                /*thinkEngineVersion*/ null,
                /*title*/ "Kit-name resolver for wish: " + abbrev(wish, 60),
                /*goal*/ null,
                /*parentProcessId*/ null,
                applied.params(),
                applied.name(),
                applied.promptOverride(),
                applied.promptOverrideAppend(),
                applied.promptMode(),
                applied.dataRelayCorrection(),
                applied.effectiveAllowedTools(),
                applied.connectionProfile(),
                applied.defaultActiveSkills(),
                applied.allowedSkills() == null
                        ? null : new java.util.LinkedHashSet<>(applied.allowedSkills()));
    }

    private void driveSynchronously(ThinkProcessDocument child) {
        try {
            laneScheduler.submit(child.getId(),
                    () -> thinkEngineService.start(child)).get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(
                    "Kit-resolver interrupted child='" + child.getId() + "'", ie);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() == null ? ee : ee.getCause();
            throw new RuntimeException(
                    "Kit-resolver turn failed child='" + child.getId()
                            + "': " + cause.getMessage(), cause);
        }
    }

    // ──────────────────── Reply parsing ────────────────────

    private @Nullable ParsedReply readReply(
            String tenantId, String sessionId, String childId) {
        List<ChatMessageDocument> history = chatMessageService.history(
                tenantId, sessionId, childId);
        ChatMessageDocument last = lastAssistant(history);
        if (last == null) {
            log.warn("KitNameResolver: jeltz produced no assistant message child='{}'",
                    childId);
            return null;
        }
        JsonNode wrapper;
        try {
            wrapper = jsonMapper.readTree(last.getContent());
        } catch (Exception e) {
            log.warn("KitNameResolver: failed to parse jeltz reply child='{}': {}",
                    childId, e.getMessage());
            return null;
        }
        if (!wrapper.path("success").asBoolean(false)) {
            log.warn("KitNameResolver: jeltz reported failure child='{}' error='{}' message='{}'",
                    childId,
                    wrapper.path("error").asText("?"),
                    wrapper.path("message").asText(""));
            return null;
        }
        JsonNode data = wrapper.path("data");
        return new ParsedReply(
                data.path("decision").asText(""),
                nullIfBlank(data.path("kitName").asText("")),
                data.path("rationale").asText(""));
    }

    private static @Nullable ChatMessageDocument lastAssistant(List<ChatMessageDocument> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessageDocument m = history.get(i);
            if (m.getRole() == ChatRole.ASSISTANT
                    && m.getContent() != null
                    && !m.getContent().isBlank()) {
                return m;
            }
        }
        return null;
    }

    // ──────────────────── Prompt assembly ────────────────────

    /**
     * Build the body the Jeltz call sees. Embeds the catalog inline —
     * the resolver decision is closed-vocab, so the LLM has to see the
     * exact set of valid {@code name} values. Lists are stable
     * (catalog order) so prompt-caching can hit on repeat resolutions
     * with the same catalog snapshot.
     */
    static String buildPrompt(String wish, List<ProjectKitEntry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are the Vance project-kits resolver. Map a free-text "
                + "kit wish to one entry in the tenant catalog below, "
                + "or report no match.\n\n");
        sb.append("USER WISH: ").append(quote(wish)).append("\n\n");
        sb.append("CATALOG ENTRIES (closed vocabulary — `kitName` in your "
                + "reply MUST be one of these `name` values verbatim when "
                + "decision=MATCH):\n");
        for (ProjectKitEntry e : entries) {
            sb.append("- name: ").append(quote(e.getName())).append('\n');
            if (e.getTitle() != null && !e.getTitle().isBlank()) {
                sb.append("  title: ").append(quote(e.getTitle())).append('\n');
            }
            if (e.getDescription() != null && !e.getDescription().isBlank()) {
                sb.append("  description: ").append(quote(e.getDescription())).append('\n');
            }
        }
        sb.append('\n');
        sb.append("MATCHING RULES:\n");
        sb.append("- Match on INTENT, not surface keywords. "
                + "\"Schul-Aufsatz\" / \"essay kit\" / \"Pro/Contra\" all "
                + "intend a school-essay-shaped output even when the "
                + "wording differs from the catalog description.\n");
        sb.append("- Be conservative: prefer NONE when no entry clearly "
                + "fits. A forced MATCH installs a wrong kit, which is "
                + "worse than letting the user clarify.\n");
        sb.append("- A URL is NOT a catalog name. If the wish looks like "
                + "a git URL (`https://…`, `git@…`) or a file URL "
                + "(`file:///…`) — return NONE with rationale "
                + "\"wish is a URL, install directly via kit_install\".\n");
        sb.append("- The wish may be in any language; the catalog `name` "
                + "and `description` are the source of truth.\n");
        sb.append('\n');
        sb.append("Reply matching the JSON schema. `kitName` must be a "
                + "verbatim catalog `name` when MATCH, `null` when NONE. "
                + "`rationale` must be 1-2 sentences explaining the choice.\n");
        return sb.toString();
    }

    private static String quote(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
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

    /** Internal — Jeltz reply parsed but not yet validated against the catalog. */
    private record ParsedReply(String decision, @Nullable String kitName, String rationale) {
        // Compact-canonical accessors so the resolver code reads cleanly.
        @Override public String decision() { return decision == null ? "" : decision.toUpperCase(Locale.ROOT); }
    }
}
