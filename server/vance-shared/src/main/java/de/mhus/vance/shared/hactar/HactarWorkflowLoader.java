package de.mhus.vance.shared.hactar;

import de.mhus.vance.api.hactar.HactarErrorKind;
import de.mhus.vance.api.hactar.HactarTaskType;
import de.mhus.vance.api.hactar.HactarWorkflowSource;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.shared.home.HomeBootstrapService;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.resolver.Resolver;

/**
 * Cascade-aware workflow loader for Hactar. Reads one YAML per
 * workflow through {@link DocumentService#lookupCascade} /
 * {@link DocumentService#listByPrefixCascade}:
 * {@code project → _vance/workflows/<name>.yaml}.
 *
 * <p>Unlike recipes there is intentionally no resource tier — workflows
 * are always project- or tenant-specific (plan §12, mirrors scheduler).
 *
 * <p>Parse errors on individual entries surface to the caller via
 * {@link HactarWorkflowParseException}; bulk listings ({@link #listAll})
 * log and skip — a single broken doc must not poison project bootstrap
 * or admin views.
 *
 * <p>No in-memory cache. Each {@link #load} hits the document layer
 * once; callers that need a frozen snapshot (the running
 * {@code HactarProcess}) capture the YAML in {@code StartRecord} at
 * spawn time and never re-resolve.
 */
@Service
@ConditionalOnProperty(
        value = "vance.services.hactar",
        havingValue = "true",
        matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class HactarWorkflowLoader {

    /** Path prefix for workflow documents in any cascade tier. */
    public static final String WORKFLOW_PATH_PREFIX = "_vance/workflows/";

    /** File suffix kept on the document path; the workflow name itself does not carry it. */
    public static final String WORKFLOW_PATH_SUFFIX = ".yaml";

    private final DocumentService documentService;

    /**
     * Resolve a single workflow by name in the project/_vance cascade.
     * Returns empty if no tier carries it.
     */
    public Optional<ResolvedHactarWorkflow> load(
            String tenantId, @Nullable String projectId, String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        String norm = normalizedName(name);
        String path = pathFor(norm);
        Optional<LookupResult> hit = documentService.lookupCascade(
                tenantId, effectiveProjectId(projectId), path);
        if (hit.isEmpty()) return Optional.empty();
        LookupResult result = hit.get();
        if (result.source() == LookupResult.Source.RESOURCE) {
            // Defensive: resource layer is not part of the workflow design.
            log.warn("HactarWorkflowLoader: ignoring resource-layer workflow at '{}'", result.path());
            return Optional.empty();
        }
        try {
            return Optional.of(parse(norm, result));
        } catch (RuntimeException e) {
            throw new HactarWorkflowParseException(
                    "Failed to parse workflow '" + name + "' from "
                            + result.source() + " at path '" + result.path()
                            + "': " + e.getMessage(), e);
        }
    }

    /**
     * Every workflow visible to the project: project entries override
     * {@code _vance/workflows/} entries by name. Malformed entries are
     * logged and skipped — the rest of the bootstrap continues.
     */
    public List<ResolvedHactarWorkflow> listAll(String tenantId, @Nullable String projectId) {
        Map<String, LookupResult> hits = documentService.listByPrefixCascade(
                tenantId, effectiveProjectId(projectId), WORKFLOW_PATH_PREFIX);
        List<ResolvedHactarWorkflow> out = new ArrayList<>(hits.size());
        for (Map.Entry<String, LookupResult> e : hits.entrySet()) {
            String path = e.getKey();
            String name = nameFromPath(path);
            if (name == null) continue;
            LookupResult hit = e.getValue();
            if (hit.source() == LookupResult.Source.RESOURCE) continue;
            try {
                out.add(parse(name, hit));
            } catch (RuntimeException ex) {
                log.warn("HactarWorkflowLoader: skipping malformed workflow path='{}' source={}: {}",
                        path, hit.source(), ex.getMessage());
            }
        }
        return out;
    }

    /**
     * Validate a YAML body without persisting it. Used by REST and the
     * agent tools before writing — malformed input never reaches the
     * document layer.
     *
     * @throws HactarWorkflowParseException with a field-level error message
     */
    public ResolvedHactarWorkflow validateYaml(String name, String yaml) {
        String norm = normalizedName(name);
        try {
            return parse(norm, syntheticHit(norm, yaml));
        } catch (RuntimeException ex) {
            throw new HactarWorkflowParseException(
                    "workflow YAML invalid: " + ex.getMessage(), ex);
        }
    }

    private static String pathFor(String name) {
        return WORKFLOW_PATH_PREFIX + name + WORKFLOW_PATH_SUFFIX;
    }

    private static String normalizedName(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static String effectiveProjectId(@Nullable String projectId) {
        return (projectId == null || projectId.isBlank())
                ? HomeBootstrapService.VANCE_PROJECT_NAME : projectId;
    }

    private static @Nullable String nameFromPath(String path) {
        if (!path.startsWith(WORKFLOW_PATH_PREFIX)) return null;
        if (!path.endsWith(WORKFLOW_PATH_SUFFIX)) return null;
        String stem = path.substring(
                WORKFLOW_PATH_PREFIX.length(),
                path.length() - WORKFLOW_PATH_SUFFIX.length());
        return stem.isBlank() ? null : stem;
    }

    private static LookupResult syntheticHit(String name, String yaml) {
        return new LookupResult(
                WORKFLOW_PATH_PREFIX + name + WORKFLOW_PATH_SUFFIX,
                yaml,
                LookupResult.Source.PROJECT,
                /*document*/ null);
    }

    // ──────────── parser ────────────

    /**
     * YAML resolver that uses YAML-1.2 boolean semantics — only
     * {@code true}/{@code false} are coerced to {@link Boolean}.
     * Default SnakeYAML uses YAML-1.1 which also treats
     * {@code on}/{@code off}/{@code yes}/{@code no} as booleans, which
     * would silently rewrite the {@code on:} keys workflows depend on
     * to {@link Boolean#TRUE} — the same gotcha GitHub Actions avoids
     * by adopting YAML-1.2 in its own parser.
     */
    private static final Resolver YAML_1_2_RESOLVER = new Resolver() {
        @Override
        protected void addImplicitResolvers() {
            addImplicitResolver(Tag.BOOL,
                    Pattern.compile("^(?:true|True|TRUE|false|False|FALSE)$"),
                    "tTfF");
            addImplicitResolver(Tag.INT,
                    Pattern.compile("^(?:[-+]?(?:[0-9][0-9_]*))$"),
                    "-+0123456789");
            addImplicitResolver(Tag.FLOAT,
                    Pattern.compile("^(?:[-+]?(?:[0-9][0-9_]*)?\\.[0-9_]+(?:[eE][-+]?[0-9]+)?"
                            + "|[-+]?(?:[0-9][0-9_]*)(?:[eE][-+]?[0-9]+)|[-+]?\\.(?:inf|Inf|INF)|\\.(?:nan|NaN|NAN))$"),
                    "-+0123456789.");
            addImplicitResolver(Tag.MERGE, Pattern.compile("^(?:<<)$"), "<");
            addImplicitResolver(Tag.NULL,
                    Pattern.compile("^(?:~|null|Null|NULL| *)$"),
                    "~nN\0");
            addImplicitResolver(Tag.NULL, Pattern.compile("^$"), null);
            addImplicitResolver(Tag.TIMESTAMP,
                    Pattern.compile("^(?:[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]"
                            + "|[0-9][0-9][0-9][0-9]-[0-9][0-9]?-[0-9][0-9]?"
                            + "(?:[Tt]|[ \\t]+)[0-9][0-9]?:[0-9][0-9]:[0-9][0-9](?:\\.[0-9]*)?"
                            + "(?:[ \\t]*(?:Z|[-+][0-9][0-9]?(?::[0-9][0-9])?))?)$"),
                    "0123456789");
        }
    };

    private static Yaml createWorkflowYamlParser() {
        Yaml y = new Yaml();
        // Replace the default 1.1 resolver with our 1.2 variant — see field javadoc.
        java.lang.reflect.Field f;
        try {
            f = Yaml.class.getDeclaredField("resolver");
            f.setAccessible(true);
            f.set(y, YAML_1_2_RESOLVER);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("SnakeYAML internals changed — cannot install YAML-1.2 resolver", ex);
        }
        return y;
    }

    @SuppressWarnings("unchecked")
    private static ResolvedHactarWorkflow parse(String name, LookupResult hit) {
        Yaml yaml = createWorkflowYamlParser();
        Object parsed = yaml.load(hit.content());
        if (parsed == null) {
            throw new IllegalStateException("workflow YAML is empty");
        }
        if (!(parsed instanceof Map<?, ?> rawMap)) {
            throw new IllegalStateException("workflow YAML must have a top-level map");
        }
        Map<String, Object> spec = (Map<String, Object>) rawMap;

        String description = stringOrNull(spec.get("description"));
        String version = stringOrNull(spec.get("version"));
        String startState = stringOrThrow(spec.get("start"), "start");

        Map<String, HactarParameterSpec> parameters = parseParameters(spec.get("parameters"));
        HactarBoundsSpec bounds = parseBounds(spec.get("bounds"));
        List<String> allowedTools = stringList(spec.get("allowedTools"), "allowedTools");
        List<String> tags = stringList(spec.get("tags"), "tags");

        Map<String, HactarStateSpec> states = parseStates(spec.get("states"));
        if (states.isEmpty()) {
            throw new IllegalStateException("workflow has no 'states' — at least one is required");
        }
        if (!states.containsKey(startState)) {
            throw new IllegalStateException(
                    "'start: " + startState + "' does not match any state in 'states:'");
        }

        validateTransitionTargets(states);

        DocumentDocument doc = hit.document();
        return new ResolvedHactarWorkflow(
                name,
                hit.content(),
                mapSource(hit.source()),
                doc == null ? null : doc.getId(),
                doc == null ? null : doc.getCreatedBy(),
                description,
                version,
                startState,
                Map.copyOf(parameters),
                Map.copyOf(states),
                bounds,
                allowedTools,
                tags);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, HactarParameterSpec> parseParameters(@Nullable Object raw) {
        if (raw == null) return Map.of();
        if (!(raw instanceof Map<?, ?> pm)) {
            throw new IllegalStateException("'parameters' must be a map");
        }
        Map<String, HactarParameterSpec> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : pm.entrySet()) {
            String key = String.valueOf(e.getKey());
            if (!(e.getValue() instanceof Map<?, ?> v)) {
                throw new IllegalStateException(
                        "parameter '" + key + "' must be a map of {type, required?, default?}");
            }
            Map<String, Object> entry = (Map<String, Object>) v;
            String type = stringOrThrow(entry.get("type"), "parameters." + key + ".type");
            boolean required = entry.get("required") instanceof Boolean b && b;
            Object defaultValue = entry.get("default");
            out.put(key, new HactarParameterSpec(type, required, defaultValue));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static HactarBoundsSpec parseBounds(@Nullable Object raw) {
        if (raw == null) return HactarBoundsSpec.empty();
        if (!(raw instanceof Map<?, ?> bm)) {
            throw new IllegalStateException("'bounds' must be a map");
        }
        Map<String, Object> b = (Map<String, Object>) bm;
        return new HactarBoundsSpec(
                numberAsDoubleOrNull(b.get("maxTotalCostUsd"), "bounds.maxTotalCostUsd"),
                numberAsLongOrNull(b.get("maxWallclockSeconds"), "bounds.maxWallclockSeconds"),
                numberAsIntOrNull(b.get("maxTaskSpawns"), "bounds.maxTaskSpawns"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, HactarStateSpec> parseStates(@Nullable Object raw) {
        if (raw == null) {
            throw new IllegalStateException("missing required field 'states'");
        }
        if (!(raw instanceof Map<?, ?> sm)) {
            throw new IllegalStateException("'states' must be a map of state-name → state-def");
        }
        Map<String, HactarStateSpec> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : sm.entrySet()) {
            String name = String.valueOf(e.getKey());
            if (!(e.getValue() instanceof Map<?, ?> v)) {
                throw new IllegalStateException(
                        "state '" + name + "' must be a map");
            }
            out.put(name, parseState(name, (Map<String, Object>) v));
        }
        return out;
    }

    private static HactarStateSpec parseState(String name, Map<String, Object> raw) {
        HactarTaskType type = parseTaskType(raw.get("type"), name);
        String description = stringOrNull(raw.get("description"));
        Integer timeoutSeconds = numberAsIntOrNull(
                raw.get("timeoutSeconds"), "states." + name + ".timeoutSeconds");
        String storeAs = stringOrNull(raw.get("storeAs"));

        Map<String, String> onOutcomes = parseOnBlock(raw.get("on"), name);
        Map<HactarErrorKind, String> catchKinds = parseCatchBlock(raw.get("catch"), name);
        List<HactarTransition> transitions = parseTransitions(raw.get("transitions"), name, type);
        HactarRetrySpec retry = parseRetry(raw.get("retry"), name);

        // Everything left over is type-specific — copy verbatim for the type-executor.
        Map<String, Object> spec = new LinkedHashMap<>(raw);
        spec.keySet().removeAll(Set.of(
                "type", "description", "timeoutSeconds", "storeAs",
                "on", "catch", "transitions", "retry"));

        return new HactarStateSpec(
                name, type, description, timeoutSeconds, storeAs,
                Map.copyOf(onOutcomes),
                Map.copyOf(catchKinds),
                List.copyOf(transitions),
                retry,
                Map.copyOf(spec));
    }

    private static HactarTaskType parseTaskType(@Nullable Object raw, String stateName) {
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new IllegalStateException(
                    "state '" + stateName + "' is missing required 'type'");
        }
        String norm = s.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return HactarTaskType.valueOf(norm);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "state '" + stateName + "' has unknown type '" + s + "'");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> parseOnBlock(@Nullable Object raw, String stateName) {
        if (raw == null) return Map.of();
        if (!(raw instanceof Map<?, ?> m)) {
            throw new IllegalStateException(
                    "state '" + stateName + "' has non-map 'on:' block");
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ((Map<String, Object>) m).entrySet()) {
            String outcome = String.valueOf(e.getKey());
            if (!(e.getValue() instanceof String target) || target.isBlank()) {
                throw new IllegalStateException(
                        "state '" + stateName + "' has non-string target for outcome '"
                                + outcome + "'");
            }
            out.put(outcome, target);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<HactarErrorKind, String> parseCatchBlock(@Nullable Object raw, String stateName) {
        if (raw == null) return Map.of();
        if (!(raw instanceof Map<?, ?> m)) {
            throw new IllegalStateException(
                    "state '" + stateName + "' has non-map 'catch:' block");
        }
        Map<HactarErrorKind, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ((Map<String, Object>) m).entrySet()) {
            String key = String.valueOf(e.getKey()).trim().toUpperCase(Locale.ROOT).replace('-', '_');
            HactarErrorKind kind;
            try {
                kind = HactarErrorKind.valueOf(key);
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException(
                        "state '" + stateName + "' catch-key '" + e.getKey()
                                + "' is not a known error kind");
            }
            if (!(e.getValue() instanceof String target) || target.isBlank()) {
                throw new IllegalStateException(
                        "state '" + stateName + "' catch target for '" + key
                                + "' must be a non-blank state name");
            }
            out.put(kind, target);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<HactarTransition> parseTransitions(
            @Nullable Object raw, String stateName, HactarTaskType type) {
        if (raw == null) return List.of();
        if (type != HactarTaskType.CONDITION_TASK) {
            throw new IllegalStateException(
                    "state '" + stateName + "' has 'transitions:' but type is " + type
                            + " — only CONDITION_TASK accepts an ordered transition list");
        }
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException(
                    "state '" + stateName + "' 'transitions:' must be a list");
        }
        List<HactarTransition> out = new ArrayList<>(list.size());
        boolean elseSeen = false;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> entry)) {
                throw new IllegalStateException(
                        "state '" + stateName + "' transition entry must be a map");
            }
            Map<String, Object> e = (Map<String, Object>) entry;
            String target;
            String condition;
            if (e.containsKey("else")) {
                condition = null;
                target = stringOrThrow(e.get("else"), "transitions[else].target");
                elseSeen = true;
            } else {
                if (elseSeen) {
                    throw new IllegalStateException(
                            "state '" + stateName + "' has transition entries after the 'else:' branch");
                }
                condition = stringOrThrow(e.get("if"), "transitions[if]");
                target = stringOrThrow(e.get("to"), "transitions[to]");
            }
            out.add(new HactarTransition(condition, target));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static HactarRetrySpec parseRetry(@Nullable Object raw, String stateName) {
        if (raw == null) return HactarRetrySpec.none();
        if (!(raw instanceof Map<?, ?> m)) {
            throw new IllegalStateException(
                    "state '" + stateName + "' has non-map 'retry:' block");
        }
        Map<String, Object> r = (Map<String, Object>) m;
        int maxAttempts = numberAsIntOrDefault(r.get("maxAttempts"), 1,
                "states." + stateName + ".retry.maxAttempts");
        if (maxAttempts < 1) {
            throw new IllegalStateException(
                    "state '" + stateName + "' retry.maxAttempts must be ≥ 1");
        }
        int backoff = numberAsIntOrDefault(r.get("backoffSeconds"), 30,
                "states." + stateName + ".retry.backoffSeconds");
        if (backoff < 0) {
            throw new IllegalStateException(
                    "state '" + stateName + "' retry.backoffSeconds must be ≥ 0");
        }
        Set<HactarErrorKind> onKinds = parseErrorKindList(r.get("on"),
                "states." + stateName + ".retry.on");
        return new HactarRetrySpec(maxAttempts, onKinds, backoff);
    }

    private static Set<HactarErrorKind> parseErrorKindList(@Nullable Object raw, String fieldPath) {
        if (raw == null) return Set.of();
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException("'" + fieldPath + "' must be a list");
        }
        EnumSet<HactarErrorKind> set = EnumSet.noneOf(HactarErrorKind.class);
        for (Object item : list) {
            if (!(item instanceof String s) || s.isBlank()) {
                throw new IllegalStateException(
                        "'" + fieldPath + "' contains a non-string or blank entry");
            }
            String key = s.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            try {
                set.add(HactarErrorKind.valueOf(key));
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException(
                        "'" + fieldPath + "' has unknown error kind '" + s + "'");
            }
        }
        return Set.copyOf(set);
    }

    private static void validateTransitionTargets(Map<String, HactarStateSpec> states) {
        Set<String> known = states.keySet();
        for (HactarStateSpec state : states.values()) {
            for (Map.Entry<String, String> e : state.onOutcomes().entrySet()) {
                if (!known.contains(e.getValue())) {
                    throw new IllegalStateException(
                            "state '" + state.name() + "' outcome '" + e.getKey()
                                    + "' points to unknown state '" + e.getValue() + "'");
                }
            }
            for (Map.Entry<HactarErrorKind, String> e : state.catchKinds().entrySet()) {
                if (!known.contains(e.getValue())) {
                    throw new IllegalStateException(
                            "state '" + state.name() + "' catch '" + e.getKey()
                                    + "' points to unknown state '" + e.getValue() + "'");
                }
            }
            for (HactarTransition t : state.transitions()) {
                if (!known.contains(t.target())) {
                    throw new IllegalStateException(
                            "state '" + state.name() + "' transition target '"
                                    + t.target() + "' is not a declared state");
                }
            }
        }
    }

    // ──────────── small helpers ────────────

    private static HactarWorkflowSource mapSource(LookupResult.Source source) {
        return switch (source) {
            case PROJECT -> HactarWorkflowSource.PROJECT;
            case VANCE -> HactarWorkflowSource.TENANT;
            case RESOURCE -> throw new IllegalStateException(
                    "resource layer is not allowed for workflows");
        };
    }

    private static String stringOrThrow(@Nullable Object raw, String fieldName) {
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new IllegalStateException(
                    "missing required field '" + fieldName + "' (must be a non-empty string)");
        }
        return s;
    }

    private static @Nullable String stringOrNull(@Nullable Object raw) {
        return raw instanceof String s && !s.isBlank() ? s : null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(@Nullable Object raw, String fieldName) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException("'" + fieldName + "' must be a list");
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String s) || s.isBlank()) {
                throw new IllegalStateException(
                        "'" + fieldName + "' contains a non-string or blank entry");
            }
            out.add(s);
        }
        return List.copyOf(out);
    }

    private static @Nullable Integer numberAsIntOrNull(@Nullable Object raw, String fieldPath) {
        if (raw == null) return null;
        if (raw instanceof Number n) return n.intValue();
        throw new IllegalStateException("'" + fieldPath + "' must be a number");
    }

    private static int numberAsIntOrDefault(@Nullable Object raw, int fallback, String fieldPath) {
        Integer val = numberAsIntOrNull(raw, fieldPath);
        return val == null ? fallback : val;
    }

    private static @Nullable Long numberAsLongOrNull(@Nullable Object raw, String fieldPath) {
        if (raw == null) return null;
        if (raw instanceof Number n) return n.longValue();
        throw new IllegalStateException("'" + fieldPath + "' must be a number");
    }

    private static @Nullable Double numberAsDoubleOrNull(@Nullable Object raw, String fieldPath) {
        if (raw == null) return null;
        if (raw instanceof Number n) return n.doubleValue();
        throw new IllegalStateException("'" + fieldPath + "' must be a number");
    }
}
