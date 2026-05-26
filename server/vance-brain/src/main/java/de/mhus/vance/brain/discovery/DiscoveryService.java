package de.mhus.vance.brain.discovery;

import de.mhus.vance.brain.ai.light.LightLlmException;
import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Backend of the {@code how_do_i} discovery tool. Renders the source
 * catalog for the tenant, hands it to {@link LightLlmService} via the
 * {@code how-do-i} recipe (Pebble vars {@code intent} +
 * {@code sources}), normalises the LLM's structured reply into a
 * {@link DiscoveryResult} and cross-checks returned capability names
 * against the catalog so hallucinated matches surface as a friendly
 * error rather than as a dead {@code manual_read}.
 *
 * <p>Thin wrapper — all heavy lifting (recipe-resolve, Pebble-render,
 * schema-validation loop) lives in {@link LightLlmService}.
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

    /**
     * Resolve an intent against the catalog. Throws
     * {@link LightLlmException} for non-recoverable failures (recipe
     * missing, LLM 5xx). Hallucinated capability names are downgraded
     * to a {@code hint}.
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

        Map<String, Object> raw = lightLlm.callForJson(LightLlmRequest.builder()
                .recipeName(DEFAULT_RECIPE_NAME)
                .userPrompt(intent)
                .pebbleVars(Map.of("intent", intent, "sources", catalog))
                .schema(DISCOVERY_SCHEMA)
                .tenantId(tenantId)
                .projectId(projectId)
                .processId(processId)
                .build());

        return normalise(intent, raw, catalog);
    }

    // ──────────────────── Reply normalisation ────────────────────

    private DiscoveryResult normalise(String intent, Map<String, Object> raw, String catalog) {
        DiscoveryResult.DiscoveryResultBuilder builder = DiscoveryResult.builder()
                .intent(intent)
                .alternatives(List.of());

        // Loaded — confident single match (takes precedence)
        Object loaded = raw.get("loaded");
        if (loaded instanceof Map<?, ?> loadedMap) {
            DiscoveryResult.Match match = toMatch(loadedMap, /*expectContent*/ true);
            if (match != null && knownCapability(match.getName(), catalog)) {
                return builder.loaded(match).build();
            }
            if (match != null) {
                log.info("DiscoveryService: LLM returned unknown capability '{}' — "
                        + "downgrading to hint", match.getName());
                return builder
                        .hint("LLM proposed '" + match.getName()
                                + "' but it isn't in the catalog — refine the intent.")
                        .build();
            }
        }

        // Alternatives — list of candidates
        Object alternatives = raw.get("alternatives");
        if (alternatives instanceof List<?> list && !list.isEmpty()) {
            List<DiscoveryResult.Match> filtered = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> entry)) continue;
                DiscoveryResult.Match m = toMatch(entry, /*expectContent*/ false);
                if (m == null) continue;
                if (!knownCapability(m.getName(), catalog)) {
                    log.debug("DiscoveryService: dropping unknown alternative '{}'", m.getName());
                    continue;
                }
                filtered.add(m);
            }
            if (!filtered.isEmpty()) {
                return builder.alternatives(filtered).build();
            }
        }

        // Hint — no match
        Object hint = raw.get("hint");
        if (hint instanceof String s && !s.isBlank()) {
            return builder.hint(s).build();
        }

        // Fallback when the LLM somehow returned an empty shape that
        // satisfied the schema but carries no useful payload.
        return builder
                .hint("Discovery returned no usable result — try a more concrete intent.")
                .build();
    }

    private static DiscoveryResult.@Nullable Match toMatch(
            Map<?, ?> raw, boolean expectContent) {
        Object name = raw.get("name");
        if (!(name instanceof String n) || n.isBlank()) return null;
        DiscoveryResult.Match.MatchBuilder b = DiscoveryResult.Match.builder().name(n);
        if (raw.get("type") instanceof String t) b.type(t);
        if (raw.get("source") instanceof String src) b.source(src);
        if (expectContent && raw.get("content") instanceof String c) {
            b.content(c);
        }
        if (raw.get("summary") instanceof String summary) b.summary(summary);
        if (raw.get("score") instanceof Number sc) b.score(sc.doubleValue());
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
