package de.mhus.vance.brain.discovery;

import de.mhus.vance.brain.ai.light.LightLlmException;
import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.api.tools.ToolSpec;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Backend of the {@code how_do_i} discovery tool.
 *
 * <p>Two-stage contract:
 *
 * <ol>
 *   <li><b>Discovery LLM picks a pointer.</b> The {@link LightLlmService}
 *       is handed the source catalog — cached manual / skill cards from
 *       {@link SourceCatalogBuilder} plus the calling session's callable
 *       tools, appended per call (see
 *       {@link #discover(String, String, String, String, Set, List)}) —
 *       together with the caller's intent, and returns a structured
 *       shape with {@code name + type + source}
 *       only — never raw content.</li>
 *   <li><b>Server resolves the body.</b> When the pick is
 *       {@code type: "manual"} and the name exists in the document
 *       cascade at {@code manuals/<name>.md}, the manual body is
 *       loaded server-side and inlined into the response. The caller
 *       gets the body in one hop — no follow-up {@code manual_read}
 *       needed for the happy path.</li>
 * </ol>
 *
 * <p>Anti-hallucination retry: if the LLM picks a name that isn't in
 * the catalog or can't be loaded from disk, the call is retried up to
 * {@link #MAX_DISCOVERY_ATTEMPTS} times with a Pebble correction
 * variable that lists the bad names so the LLM doesn't repeat them.
 * After the budget is exhausted, the result downgrades to a
 * {@code hint}.
 *
 * <p>Skills and tools never carry inlined content — they're stubs
 * in the catalog, not on-disk markdown. For those types the caller
 * uses whatever loader matches the type.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DiscoveryService {

    /**
     * Recipe used as the LightLlm config profile. Tenants can
     * override by placing their own {@code recipes/how-do-i.yaml}
     * in the document cascade — internal marker is preserved.
     */
    static final String DEFAULT_RECIPE_NAME = "how-do-i";

    /** Max passes through the LLM before downgrading to a {@code hint}.
     *  Each iteration injects a correction note listing prior bad
     *  picks so the model doesn't repeat them. */
    static final int MAX_DISCOVERY_ATTEMPTS = 3;

    /** Path prefix where manual bodies live in the cascade — mirrors
     *  {@link SourceCatalogBuilder} so the auto-load resolves
     *  exactly the bodies the catalog summarised from.
     *  Manuals are system-managed, so they live under the
     *  {@code _vance/} folder convention used for every kit-installed
     *  artifact (matches kit-manifest, hooks, scheduler, workflows,
     *  events, tool-templates). */
    static final String MANUAL_PATH_PREFIX = "_vance/manuals/";

    /**
     * JsonSchemaLight description of the expected reply shape. The
     * top-level object must carry exactly one of {@code loaded},
     * {@code alternatives}, or {@code hint}; we accept any combination
     * and pick the first non-empty one at parse time. Keeping the
     * schema permissive lets the LLM pick the natural shape without
     * tripping on artificial "exactly one" wording.
     */
    static final Map<String, Object> DISCOVERY_SCHEMA = Map.of(
            "type", "object");

    private static final Pattern CAPABILITY_HEADER =
            Pattern.compile("(?m)^###\\s+(?<name>\\S+)\\s*$");

    private final SourceCatalogService catalogService;
    private final LightLlmService lightLlm;
    private final DocumentService documentService;

    /**
     * Resolve an intent against the catalog. Throws
     * {@link LightLlmException} for non-recoverable failures (recipe
     * missing, LLM 5xx). Hallucinated capability names trigger a
     * bounded retry loop; if no usable answer emerges, the result is
     * a {@code hint} listing the bad picks.
     */
    public DiscoveryResult discover(
            String intent,
            String tenantId,
            @Nullable String projectId,
            @Nullable String processId) {
        if (intent == null || intent.isBlank()) {
            throw new IllegalArgumentException("intent is required");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        return discoverWithCatalog(
                intent, tenantId, projectId, processId,
                catalogService.renderForTenant(tenantId, projectId));
    }

    /**
     * Allow-set-aware variant. {@code allowedTools} is the calling
     * engine's tool whitelist (typically {@code ContextToolsApi.allowed()}).
     * The source catalog is filtered against this set via
     * {@link CatalogFilter} before being passed to the LLM — tools
     * the engine cannot invoke and manuals whose {@code requires-tools}
     * header isn't fully satisfied are dropped. {@code null} or empty
     * means "no restriction" (full catalog).
     */
    public DiscoveryResult discover(
            String intent,
            String tenantId,
            @Nullable String projectId,
            @Nullable String processId,
            @Nullable Set<String> allowedTools) {
        return discover(intent, tenantId, projectId, processId, allowedTools, List.of());
    }

    /**
     * Process-aware variant — the one engines should use.
     *
     * <p>{@code processTools} is what the calling process can actually
     * invoke ({@code ContextToolsApi.listAll()}), and it is the
     * <em>source</em> of the catalog's tool section, not merely a filter
     * over it. That distinction is the whole point: the cached snapshot
     * is built from Spring {@code Tool} beans, and client-registered
     * tools — every {@code client_*} tool a connected Foot brings, and
     * every MCP pack tool behind them — are not beans. They are resolved
     * per session by {@code ClientToolSource}, so a filter could only
     * ever remove them from a catalog they were never in. Asking
     * {@code how_do_i} "how do I take a screenshot" while a browser MCP
     * pack is connected therefore answered "no match".
     *
     * <p>The split also lines the two lifetimes up with their scopes:
     * manuals and skills belong to the tenant/project and stay cached,
     * tools belong to the session and are rendered per call.
     *
     * <p>{@code allowedTools} still filters the cached half — a manual
     * whose {@code requires-tools} header isn't satisfied stays hidden.
     */
    public DiscoveryResult discover(
            String intent,
            String tenantId,
            @Nullable String projectId,
            @Nullable String processId,
            @Nullable Set<String> allowedTools,
            @Nullable List<ToolSpec> processTools) {
        if (intent == null || intent.isBlank()) {
            throw new IllegalArgumentException("intent is required");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        String catalog;
        if (allowedTools == null || allowedTools.isEmpty()) {
            catalog = catalogService.renderForTenant(tenantId, projectId);
        } else {
            CatalogSnapshot snapshot = catalogService.snapshotFor(tenantId, projectId);
            catalog = CatalogFilter.filter(snapshot, allowedTools);
        }
        catalog = catalog + renderProcessTools(processTools);
        return discoverWithCatalog(intent, tenantId, projectId, processId, catalog);
    }

    /**
     * Renders the session's callable tools as catalog cards. Same
     * {@code ### name} shape the builder emits, because
     * {@link #knownCapability} validates the LLM's pick by matching
     * those headers against the rendered catalog — a card in a different
     * shape would be treated as hallucinated.
     *
     * <p>Deferred tools are included: they are callable, the engine just
     * activates them on first use, and not listing them is exactly how a
     * model ends up believing a capability does not exist.
     */
    private static String renderProcessTools(@Nullable List<ToolSpec> processTools) {
        if (processTools == null || processTools.isEmpty()) {
            return "";
        }
        List<ToolSpec> primary = new ArrayList<>();
        List<ToolSpec> secondary = new ArrayList<>();
        for (ToolSpec t : processTools) {
            if (t == null || t.getName() == null || t.getName().isBlank()) continue;
            (t.isPrimary() ? primary : secondary).add(t);
        }
        primary.sort(java.util.Comparator.comparing(ToolSpec::getName));
        secondary.sort(java.util.Comparator.comparing(ToolSpec::getName));

        StringBuilder md = new StringBuilder();
        // Primary tools: full description — the model can call these directly.
        if (!primary.isEmpty()) {
            md.append("\n## Tools\n\n");
            for (ToolSpec t : primary) {
                md.append("### ").append(t.getName()).append("\n\n");
                String description = t.getDescription() == null ? "" : t.getDescription().trim();
                if (!description.isBlank()) {
                    md.append(description).append("\n\n");
                }
            }
        }
        // Deferred / non-primary tools: compact one-liners. They are callable
        // — the engine activates them on first use — and leaving them out is
        // precisely how a model concludes a capability does not exist. Full
        // descriptions would balloon this hot-path prompt, so only the first
        // sentence ships.
        if (!secondary.isEmpty()) {
            md.append("\n## More tools (activate by calling them, or via "
                    + "`tool_description name='<name>'`)\n\n");
            for (ToolSpec t : secondary) {
                md.append("### ").append(t.getName()).append("\n\n");
                String description = t.getDescription() == null ? "" : t.getDescription().trim();
                if (!description.isBlank()) {
                    md.append(firstSentence(description)).append("\n\n");
                }
            }
        }
        return md.toString();
    }

    /** First sentence (up to the first ". ") — for the compact cards. */
    private static String firstSentence(String s) {
        int dot = s.indexOf(". ");
        return dot > 0 ? s.substring(0, dot + 1) : s;
    }

    private DiscoveryResult discoverWithCatalog(
            String intent,
            String tenantId,
            @Nullable String projectId,
            @Nullable String processId,
            String catalog) {
        List<String> badPicks = new ArrayList<>();

        for (int attempt = 1; attempt <= MAX_DISCOVERY_ATTEMPTS; attempt++) {
            Map<String, Object> pebbleVars = new LinkedHashMap<>();
            pebbleVars.put("intent", intent);
            pebbleVars.put("sources", catalog);
            pebbleVars.put("correction", correctionFor(badPicks));

            Map<String, Object> raw = lightLlm.callForJson(LightLlmRequest.builder()
                    .recipeName(DEFAULT_RECIPE_NAME)
                    .userPrompt(intent)
                    .pebbleVars(pebbleVars)
                    .schema(DISCOVERY_SCHEMA)
                    .tenantId(tenantId)
                    .projectId(projectId)
                    .processId(processId)
                    .build());

            NormaliseOutcome outcome = normalise(intent, raw, catalog);
            if (outcome instanceof NormaliseOutcome.Hallucinated h) {
                log.info("DiscoveryService: attempt {}/{} picked unknown '{}' — retrying",
                        attempt, MAX_DISCOVERY_ATTEMPTS, h.name);
                badPicks.add(h.name);
                continue;
            }
            DiscoveryResult result = ((NormaliseOutcome.Resolved) outcome).result;

            // Auto-load: for a confident manual match, inline the body
            // so the caller doesn't need to bounce through manual_read.
            DiscoveryResult.Match loaded = result.getLoaded();
            if (loaded != null && "manual".equals(loaded.getType())) {
                Optional<String> body = loadManualBody(
                        loaded.getName(), tenantId, projectId);
                if (body.isPresent()) {
                    return withInlinedContent(result, loaded, body.get());
                }
                // No manual body resolved. normalise() already validated
                // the name as a known catalog capability, so check WHICH
                // section it actually lives under. If the catalog files it
                // as a tool/skill, the LLM simply mis-typed a bodiless
                // capability (e.g. hook_set — a tool with no manual file)
                // as "manual": retype it and pass it through so the caller
                // can invoke it directly, instead of burning retries on a
                // manual that will never exist and then collapsing to a
                // useless hint.
                String realType = capabilityType(loaded.getName(), catalog);
                if (!"manual".equals(realType)) {
                    log.debug("DiscoveryService: pick '{}' labelled manual but is a "
                                    + "{} with no manuals/{}.md body — returning as {} match",
                            loaded.getName(), realType, loaded.getName(), realType);
                    return relabelMatch(result, loaded, realType);
                }
                // Genuinely a manuals-section entry whose body didn't load
                // — an odd soft-hallucination (catalog and body come from
                // the same cascade). Retry for a loadable pick.
                log.info("DiscoveryService: attempt {}/{} picked '{}' which is "
                                + "in the manuals section but not loadable as "
                                + "_vance/manuals/{}.md — retrying",
                        attempt, MAX_DISCOVERY_ATTEMPTS, loaded.getName(), loaded.getName());
                badPicks.add(loaded.getName());
                continue;
            }

            // Skill/tool match, alternatives, or hint — no body to
            // inline; pass through verbatim.
            return result;
        }

        return DiscoveryResult.builder()
                .intent(intent)
                .alternatives(List.of())
                .hint("Discovery couldn't resolve a usable manual after "
                        + MAX_DISCOVERY_ATTEMPTS + " attempts. Bad picks: "
                        + String.join(", ", badPicks) + ". Refine the intent "
                        + "or call manual_list for an authoritative inventory.")
                .build();
    }

    /**
     * Pebble correction context handed back to the LLM on the next
     * attempt. Empty string on the first try so the recipe template
     * can {@code {% if correction %}}-gate the section.
     */
    private static String correctionFor(List<String> bad) {
        if (bad.isEmpty()) return "";
        return "Earlier attempts in this discovery turn picked names that "
                + "do NOT exist in the catalog: "
                + String.join(", ", bad)
                + ". Pick a DIFFERENT name that appears verbatim as a "
                + "`### <name>` header in the catalog above. If nothing "
                + "really fits, return a `hint` instead of guessing.";
    }

    /**
     * Looks up the manual body via the same document cascade that
     * {@link SourceCatalogBuilder} listed it from — keeps catalog and
     * auto-load in lockstep. Returns {@link Optional#empty()} when the
     * path doesn't resolve (which means the name was in the catalog
     * header but the body lookup failed; treated as a soft retry
     * trigger rather than a hard error).
     */
    private Optional<String> loadManualBody(String name, String tenantId, @Nullable String projectId) {
        String project = projectId == null ? "" : projectId;
        String path = MANUAL_PATH_PREFIX + name + ".md";
        return documentService.lookupCascade(tenantId, project, path)
                .map(LookupResult::content);
    }

    /** Return a copy of {@code result} with the loaded match's
     *  {@code content} replaced by the server-loaded body. */
    private static DiscoveryResult withInlinedContent(
            DiscoveryResult result, DiscoveryResult.Match loaded, String body) {
        DiscoveryResult.Match enriched = DiscoveryResult.Match.builder()
                .type(loaded.getType())
                .name(loaded.getName())
                .source(loaded.getSource())
                .summary(loaded.getSummary())
                .score(loaded.getScore())
                .content(body)
                .build();
        return DiscoveryResult.builder()
                .intent(result.getIntent())
                .loaded(enriched)
                .alternatives(result.getAlternatives())
                .hint(result.getHint())
                .build();
    }

    /** Rebuild {@code result} with the loaded match retyped (no inlined
     *  body) — used when the LLM mis-typed a bodiless tool/skill
     *  capability as a {@code "manual"}. */
    private static DiscoveryResult relabelMatch(
            DiscoveryResult result, DiscoveryResult.Match loaded, String type) {
        DiscoveryResult.Match retyped = DiscoveryResult.Match.builder()
                .type(type)
                .name(loaded.getName())
                .source(loaded.getSource())
                .summary(loaded.getSummary())
                .score(loaded.getScore())
                .build();
        return DiscoveryResult.builder()
                .intent(result.getIntent())
                .loaded(retyped)
                .alternatives(result.getAlternatives())
                .hint(result.getHint())
                .build();
    }

    /**
     * Determine a capability's real type from the catalog by finding
     * which {@code ## <Section>} block its {@code ### <name>} header sits
     * under. {@link SourceCatalogBuilder} renders exactly three sections
     * — Manuals, Skills, Tools. Defaults to {@code "tool"} when the name
     * isn't found under a recognised section (the name was already
     * validated as a known capability, so an executable default is safe).
     */
    static String capabilityType(String name, String catalog) {
        String section = "";
        for (String line : catalog.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("## ")) {
                section = trimmed.substring(3).strip().toLowerCase();
            } else if (trimmed.startsWith("### ")) {
                if (name.equals(trimmed.substring(4).strip())) {
                    if (section.startsWith("manual")) return "manual";
                    if (section.startsWith("skill")) return "skill";
                    return "tool";
                }
            }
        }
        return "tool";
    }

    // ──────────────────── Reply normalisation ────────────────────

    /** Result of parsing one LLM response. Two outcomes:
     *  {@link Resolved} = pass-through to caller; {@link Hallucinated}
     *  = bad name, retry the LLM call. */
    sealed interface NormaliseOutcome
            permits NormaliseOutcome.Resolved, NormaliseOutcome.Hallucinated {

        record Resolved(DiscoveryResult result) implements NormaliseOutcome {}
        record Hallucinated(String name) implements NormaliseOutcome {}
    }

    private NormaliseOutcome normalise(String intent, Map<String, Object> raw, String catalog) {
        DiscoveryResult.DiscoveryResultBuilder builder = DiscoveryResult.builder()
                .intent(intent)
                .alternatives(List.of());

        // Loaded — confident single match (takes precedence)
        Object loaded = raw.get("loaded");
        if (loaded instanceof Map<?, ?> loadedMap) {
            DiscoveryResult.Match match = toMatch(loadedMap);
            if (match != null && knownCapability(match.getName(), catalog)) {
                return new NormaliseOutcome.Resolved(builder.loaded(match).build());
            }
            if (match != null) {
                return new NormaliseOutcome.Hallucinated(match.getName());
            }
        }

        // Alternatives — list of candidates. Unknown names are
        // silently dropped; an empty list after filtering falls
        // through to the hint branch.
        Object alternatives = raw.get("alternatives");
        if (alternatives instanceof List<?> list && !list.isEmpty()) {
            List<DiscoveryResult.Match> filtered = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> entry)) continue;
                DiscoveryResult.Match m = toMatch(entry);
                if (m == null) continue;
                if (!knownCapability(m.getName(), catalog)) {
                    log.debug("DiscoveryService: dropping unknown alternative '{}'", m.getName());
                    continue;
                }
                filtered.add(m);
            }
            if (!filtered.isEmpty()) {
                return new NormaliseOutcome.Resolved(builder.alternatives(filtered).build());
            }
        }

        // Hint — no match
        Object hint = raw.get("hint");
        if (hint instanceof String s && !s.isBlank()) {
            return new NormaliseOutcome.Resolved(builder.hint(s).build());
        }

        // Fallback when the LLM somehow returned an empty shape that
        // satisfied the schema but carries no useful payload.
        return new NormaliseOutcome.Resolved(builder
                .hint("Discovery returned no usable result — try a more concrete intent.")
                .build());
    }

    private static DiscoveryResult.@Nullable Match toMatch(Map<?, ?> raw) {
        Object name = raw.get("name");
        if (!(name instanceof String n) || n.isBlank()) return null;
        DiscoveryResult.Match.MatchBuilder b = DiscoveryResult.Match.builder().name(n);
        if (raw.get("type") instanceof String t) b.type(t);
        if (raw.get("source") instanceof String src) b.source(src);
        if (raw.get("summary") instanceof String summary) b.summary(summary);
        if (raw.get("score") instanceof Number sc) b.score(sc.doubleValue());
        // content is server-side only — ignore anything the LLM
        // tries to inject here.
        return b.build();
    }

    /**
     * Cheap substring lookup against the catalog markdown. The
     * catalog renders every capability as {@code ### <name>}, so an
     * exact line-anchored match is reliable. Used to reject
     * hallucinated names.
     */
    static boolean knownCapability(@Nullable String name, String catalog) {
        if (name == null || name.isBlank()) return false;
        var m = CAPABILITY_HEADER.matcher(catalog);
        while (m.find()) {
            if (name.equals(m.group("name"))) return true;
        }
        return false;
    }

    /**
     * Test-only utility: discover the set of capability names from
     * a catalog string. Useful for asserting hash-stable rendering
     * in higher-level tests.
     */
    static Map<String, Integer> capabilityIndex(String catalog) {
        Map<String, Integer> out = new LinkedHashMap<>();
        var m = CAPABILITY_HEADER.matcher(catalog);
        int i = 0;
        while (m.find()) {
            out.put(m.group("name"), i++);
        }
        return out;
    }
}
