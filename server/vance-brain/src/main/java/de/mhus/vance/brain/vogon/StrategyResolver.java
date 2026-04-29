package de.mhus.vance.brain.vogon;

import de.mhus.vance.api.vogon.CheckpointSpec;
import de.mhus.vance.api.vogon.CheckpointType;
import de.mhus.vance.api.vogon.GateSpec;
import de.mhus.vance.api.vogon.PhaseSpec;
import de.mhus.vance.api.vogon.StrategySpec;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * Resolves Vogon {@link StrategySpec}s through the document cascade —
 * project → {@code _vance} → {@code classpath:vance-defaults/strategies/}.
 * Each strategy lives in its own {@code strategies/<name>.yaml} document
 * (no list wrapper); a tenant overrides exactly the strategies it cares
 * about by placing the file in its {@code _vance} project, the rest fall
 * through to the bundled defaults.
 *
 * <p>Replaces the old {@code BundledStrategyRegistry} — bundled strategies
 * are now ordinary classpath documents under {@code vance-defaults/strategies/}.
 *
 * <p>YAML schema for one strategy file:
 * <pre>
 * name: waterfall
 * description: |
 *   ...
 * version: "1"
 * paramDefaults:
 *   ...
 * phases:
 *   - name: planning
 *     worker: ${params.workerRecipes.planning}
 *     ...
 * </pre>
 *
 * <p>Variable substitution ({@code ${params.X}}, {@code ${state.X}},
 * {@code ${phases.X.…}}) stays a Vogon engine concern — phase strings
 * are kept as templates here.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StrategyResolver {

    /** Cascade folder for strategy files. */
    public static final String STRATEGIES_PREFIX = "strategies/";

    private static final String YAML_SUFFIX = ".yaml";

    private final DocumentService documentService;

    /**
     * Look up a single strategy by name. Cascade: project → {@code _vance}
     * → bundled. Returns {@link Optional#empty()} when no layer carries
     * the file or the file fails to parse.
     */
    public Optional<StrategySpec> find(String name, String tenantId, @Nullable String projectId) {
        if (name == null || name.isBlank() || tenantId == null || tenantId.isBlank()) {
            return Optional.empty();
        }
        String path = STRATEGIES_PREFIX + name.toLowerCase().trim() + YAML_SUFFIX;
        Optional<LookupResult> hit = documentService.lookupCascade(tenantId, projectId, path);
        if (hit.isEmpty()) return Optional.empty();
        try {
            return Optional.of(parseStrategy(hit.get().content(), path));
        } catch (RuntimeException e) {
            log.warn("StrategyResolver: failed to parse '{}' (source={}): {}",
                    path, hit.get().source(), e.toString());
            return Optional.empty();
        }
    }

    /**
     * List every strategy currently available to {@code (tenantId, projectId)},
     * deduplicated per name (innermost cascade source wins). Useful for
     * discovery / catalog tools.
     */
    public List<StrategySpec> all(String tenantId, @Nullable String projectId) {
        if (tenantId == null || tenantId.isBlank()) return List.of();
        Map<String, LookupResult> hits = documentService.listByPrefixCascade(
                tenantId, projectId, STRATEGIES_PREFIX);
        List<StrategySpec> out = new ArrayList<>();
        for (LookupResult result : hits.values()) {
            if (result.path() == null || !result.path().endsWith(YAML_SUFFIX)) continue;
            try {
                out.add(parseStrategy(result.content(), result.path()));
            } catch (RuntimeException e) {
                log.warn("StrategyResolver: failed to parse '{}' (source={}): {}",
                        result.path(), result.source(), e.toString());
            }
        }
        return out;
    }

    // ──────────────────── parsing ────────────────────

    @SuppressWarnings("unchecked")
    private static StrategySpec parseStrategy(String yamlContent, String pathHint) {
        Object parsed = new Yaml().load(yamlContent);
        if (!(parsed instanceof Map<?, ?> m)) {
            throw new IllegalStateException(pathHint + ": top-level YAML must be a map");
        }
        Map<String, Object> spec = toStringMap(m);
        String name = requireString(spec, "name", pathHint);
        String description = optString(spec.get("description"));
        String version = optString(spec.get("version"));
        if (version == null) version = "1";
        Object phasesRaw = spec.get("phases");
        if (!(phasesRaw instanceof List<?> phaseList) || phaseList.isEmpty()) {
            throw new IllegalStateException(
                    pathHint + ": strategy '" + name + "' must declare a non-empty 'phases:' list");
        }
        List<PhaseSpec> phases = new ArrayList<>(phaseList.size());
        for (int i = 0; i < phaseList.size(); i++) {
            Object p = phaseList.get(i);
            if (!(p instanceof Map<?, ?> pm)) {
                throw new IllegalStateException(
                        pathHint + ": strategy '" + name + "' phase[" + i + "] is not a map");
            }
            phases.add(parsePhase(toStringMap(pm), pathHint + " phases[" + i + "]"));
        }
        Map<String, Object> paramDefaults = toStringMap(spec.get("paramDefaults"));
        return StrategySpec.builder()
                .name(name)
                .description(description)
                .version(version)
                .phases(phases)
                .paramDefaults(paramDefaults)
                .build();
    }

    private static PhaseSpec parsePhase(Map<String, Object> spec, String trail) {
        String name = requireString(spec, "name", trail);
        return PhaseSpec.builder()
                .name(name)
                .worker(optString(spec.get("worker")))
                .workerInput(optString(spec.get("workerInput")))
                .checkpoint(parseCheckpoint(spec.get("checkpoint"), trail))
                .gate(parseGate(spec.get("gate"), trail))
                .build();
    }

    private static @Nullable CheckpointSpec parseCheckpoint(@Nullable Object raw, String trail) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> m)) {
            throw new IllegalStateException(trail + ".checkpoint must be a map");
        }
        Map<String, Object> spec = toStringMap(m);
        String typeStr = optString(spec.get("type"));
        CheckpointType type = typeStr == null
                ? CheckpointType.APPROVAL
                : CheckpointType.valueOf(typeStr.trim().toUpperCase());
        Object opts = spec.get("options");
        List<String> options = new ArrayList<>();
        if (opts instanceof List<?> l) {
            for (Object o : l) if (o != null) options.add(o.toString());
        }
        Object tags = spec.get("tags");
        List<String> tagList = new ArrayList<>();
        if (tags instanceof List<?> l) {
            for (Object o : l) if (o != null) tagList.add(o.toString());
        }
        return CheckpointSpec.builder()
                .type(type)
                .message(optStringOrEmpty(spec.get("message")))
                .options(options)
                .storeAs(optString(spec.get("storeAs")))
                .criticality(optString(spec.get("criticality")))
                .defaultValue(spec.get("default"))
                .tags(tagList)
                .payload(toStringMap(spec.get("payload")))
                .build();
    }

    private static @Nullable GateSpec parseGate(@Nullable Object raw, String trail) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> m)) {
            throw new IllegalStateException(trail + ".gate must be a map");
        }
        Map<String, Object> spec = toStringMap(m);
        return GateSpec.builder()
                .requires(asStringList(spec.get("requires")))
                .requiresAny(asStringList(spec.get("requiresAny")))
                .build();
    }

    private static List<String> asStringList(@Nullable Object raw) {
        if (raw == null) return new ArrayList<>();
        if (raw instanceof String s) {
            List<String> single = new ArrayList<>();
            single.add(s);
            return single;
        }
        if (raw instanceof List<?> l) {
            List<String> out = new ArrayList<>();
            for (Object o : l) if (o != null) out.add(o.toString());
            return out;
        }
        return new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toStringMap(@Nullable Object raw) {
        if (raw == null) return new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> m)) return new LinkedHashMap<>();
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    private static String requireString(Map<String, Object> spec, String key, String trail) {
        Object raw = spec.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new IllegalStateException(trail + " missing required string '" + key + "'");
        }
        return s;
    }

    private static @Nullable String optString(@Nullable Object raw) {
        return raw instanceof String s && !s.isBlank() ? s : null;
    }

    private static String optStringOrEmpty(@Nullable Object raw) {
        return raw instanceof String s ? s : "";
    }
}
