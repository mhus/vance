package de.mhus.vance.brain.discovery;

import de.mhus.vance.brain.ai.light.LightLlmException;
import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 *       is handed the source catalog (summary cards, see
 *       {@link SourceCatalogBuilder}) plus the caller's intent and
 *       returns a structured shape with {@code name + type + source}
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
     *  exactly the bodies the catalog summarised from. */
    static final String MANUAL_PATH_PREFIX = "manuals/";

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
        String catalog = catalogService.renderForTenant(tenantId, projectId);
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
                log.info("DiscoveryService: attempt {}/{} picked '{}' which is "
                                + "in the catalog header but not loadable as "
                                + "manuals/{}.md — retrying",
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
