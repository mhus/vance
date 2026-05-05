package de.mhus.vance.brain.vogon;

import de.mhus.vance.api.vogon.BranchAction;
import de.mhus.vance.api.vogon.CheckpointSpec;
import de.mhus.vance.api.vogon.CheckpointType;
import de.mhus.vance.api.vogon.DeciderCase;
import de.mhus.vance.api.vogon.DeciderSpec;
import de.mhus.vance.api.vogon.GateSpec;
import de.mhus.vance.api.vogon.LoopSpec;
import de.mhus.vance.api.vogon.PhaseSpec;
import de.mhus.vance.api.vogon.ScoreMatch;
import de.mhus.vance.api.vogon.ScorerCase;
import de.mhus.vance.api.vogon.ScorerSpec;
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
    /**
     * Parse a single strategy YAML document. Exposed as a static
     * test entry-point so unit tests can exercise the YAML schema
     * without booting Spring or wiring a {@code DocumentService}.
     */
    public static StrategySpec parseStrategy(String yamlContent, String pathHint) {
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
        ScorerSpec scorer = parseScorer(spec.get("scorer"), trail);
        DeciderSpec decider = parseDecider(spec.get("decider"), trail);
        if (scorer != null && decider != null) {
            throw new IllegalStateException(
                    trail + " phase '" + name
                            + "': scorer and decider are mutually exclusive");
        }
        LoopSpec loop = parseLoop(spec.get("loop"), trail + " loop");
        if (loop != null
                && (spec.get("worker") != null
                        || spec.get("checkpoint") != null
                        || scorer != null
                        || decider != null)) {
            throw new IllegalStateException(
                    trail + " phase '" + name
                            + "': loop-phase must not also declare worker/"
                            + "checkpoint/scorer/decider");
        }
        // Phase J — outputSchema + postActions on the worker phase.
        Map<String, Object> outputSchema = null;
        Object outputSchemaRaw = spec.get("outputSchema");
        if (outputSchemaRaw instanceof Map<?, ?> osm) {
            outputSchema = toStringMap(osm);
        }
        List<BranchAction> postActions = null;
        Object postActionsRaw = spec.get("postActions");
        if (postActionsRaw instanceof List<?> paList && !paList.isEmpty()) {
            postActions = parseActionList(paList,
                    trail + " phase '" + name + "' postActions");
        }
        Integer maxOutputCorrections = optInt(spec.get("maxOutputCorrections"));
        if (maxOutputCorrections != null && maxOutputCorrections < 0) {
            throw new IllegalStateException(
                    trail + " phase '" + name
                            + "': maxOutputCorrections must be >= 0");
        }
        if ((outputSchema != null || postActions != null)
                && (scorer != null || decider != null)) {
            // outputSchema + scorer/decider would mean two competing
            // ways to consume the worker reply on the same phase.
            throw new IllegalStateException(
                    trail + " phase '" + name + "': outputSchema/postActions "
                            + "and scorer/decider are mutually exclusive on "
                            + "the same phase");
        }
        return PhaseSpec.builder()
                .name(name)
                .worker(optString(spec.get("worker")))
                .workerInput(optString(spec.get("workerInput")))
                .checkpoint(parseCheckpoint(spec.get("checkpoint"), trail))
                .gate(parseGate(spec.get("gate"), trail))
                .loop(loop)
                .scorer(scorer)
                .decider(decider)
                .outputSchema(outputSchema)
                .postActions(postActions)
                .maxOutputCorrections(maxOutputCorrections)
                .build();
    }

    private static @Nullable LoopSpec parseLoop(@Nullable Object raw, String trail) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> m)) {
            throw new IllegalStateException(trail + " must be a map");
        }
        Map<String, Object> spec = toStringMap(m);
        Object subPhasesRaw = spec.get("subPhases");
        if (!(subPhasesRaw instanceof List<?> subList) || subList.isEmpty()) {
            throw new IllegalStateException(
                    trail + ": subPhases must be a non-empty list");
        }
        List<PhaseSpec> subPhases = new ArrayList<>(subList.size());
        for (int i = 0; i < subList.size(); i++) {
            Object sp = subList.get(i);
            if (!(sp instanceof Map<?, ?> spm)) {
                throw new IllegalStateException(
                        trail + " subPhases[" + i + "] is not a map");
            }
            PhaseSpec sub = parsePhase(toStringMap(spm), trail + " subPhases[" + i + "]");
            if (sub.getLoop() != null) {
                throw new IllegalStateException(
                        trail + " subPhases[" + i + "]: nested loops are not supported");
            }
            subPhases.add(sub);
        }
        int maxIterations = 1;
        Object maxRaw = spec.get("maxIterations");
        if (maxRaw instanceof Number n) {
            maxIterations = n.intValue();
        } else if (maxRaw != null) {
            throw new IllegalStateException(
                    trail + ".maxIterations must be a number");
        }
        if (maxIterations < 1) {
            throw new IllegalStateException(
                    trail + ".maxIterations must be >= 1");
        }
        LoopSpec.OnMaxReached onMax = LoopSpec.OnMaxReached.ESCALATE;
        Object onMaxRaw = spec.get("onMaxReached");
        if (onMaxRaw instanceof String s && !s.isBlank()) {
            String norm = s.trim().toLowerCase().replace('-', '_');
            switch (norm) {
                case "escalate" -> onMax = LoopSpec.OnMaxReached.ESCALATE;
                case "exit_ok", "exitok" -> onMax = LoopSpec.OnMaxReached.EXIT_OK;
                case "exit_fail", "exitfail" -> onMax = LoopSpec.OnMaxReached.EXIT_FAIL;
                default -> throw new IllegalStateException(
                        trail + ".onMaxReached unknown value: " + s);
            }
        }
        return LoopSpec.builder()
                .until(parseGate(spec.get("until"), trail + ".until"))
                .maxIterations(maxIterations)
                .onMaxReached(onMax)
                .subPhases(subPhases)
                .build();
    }

    private static @Nullable ScorerSpec parseScorer(@Nullable Object raw, String trail) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> m)) {
            throw new IllegalStateException(trail + ".scorer must be a map");
        }
        Map<String, Object> spec = toStringMap(m);
        String storeAs = requireString(spec, "storeAs", trail + ".scorer");
        Object casesRaw = spec.get("cases");
        if (!(casesRaw instanceof List<?> caseList) || caseList.isEmpty()) {
            throw new IllegalStateException(
                    trail + ".scorer.cases must be a non-empty list");
        }
        List<ScorerCase> cases = new ArrayList<>(caseList.size());
        for (int i = 0; i < caseList.size(); i++) {
            Object c = caseList.get(i);
            if (!(c instanceof Map<?, ?> cm)) {
                throw new IllegalStateException(
                        trail + ".scorer.cases[" + i + "] is not a map");
            }
            cases.add(parseScorerCase(toStringMap(cm), trail + ".scorer.cases[" + i + "]"));
        }
        // Validate: defaultMatch only on the last case.
        for (int i = 0; i < cases.size() - 1; i++) {
            if (cases.get(i).getWhen().isDefaultMatch()) {
                throw new IllegalStateException(
                        trail + ".scorer.cases[" + i
                                + "]: default-match must be the last case");
            }
        }
        Integer maxCorrections = optInt(spec.get("maxCorrections"));
        if (maxCorrections != null && maxCorrections < 0) {
            throw new IllegalStateException(
                    trail + ".scorer.maxCorrections must be >= 0");
        }
        return ScorerSpec.builder()
                .schema(toStringMap(spec.get("schema")))
                .storeAs(storeAs)
                .cases(cases)
                .maxCorrections(maxCorrections == null ? 2 : maxCorrections)
                .build();
    }

    private static ScorerCase parseScorerCase(Map<String, Object> spec, String trail) {
        Object whenRaw = spec.get("when");
        if (!(whenRaw instanceof Map<?, ?> wm)) {
            throw new IllegalStateException(trail + ".when must be a map");
        }
        ScoreMatch match = parseScoreMatch(toStringMap(wm), trail + ".when");
        Object doRaw = spec.get("do");
        if (!(doRaw instanceof List<?> doList)) {
            throw new IllegalStateException(trail + ".do must be a list");
        }
        List<BranchAction> actions = parseActionList(doList, trail + ".do");
        return ScorerCase.builder().when(match).doActions(actions).build();
    }

    private static ScoreMatch parseScoreMatch(Map<String, Object> spec, String trail) {
        ScoreMatch.ScoreMatchBuilder b = ScoreMatch.builder();
        int set = 0;
        if (spec.get("scoreAtLeast") instanceof Number n) {
            b.scoreAtLeast(n.doubleValue());
            set++;
        }
        if (spec.get("scoreBelow") instanceof Number n) {
            b.scoreBelow(n.doubleValue());
            set++;
        }
        Object between = spec.get("scoreBetween");
        if (between instanceof List<?> l) {
            if (l.size() != 2 || !(l.get(0) instanceof Number) || !(l.get(1) instanceof Number)) {
                throw new IllegalStateException(
                        trail + ".scoreBetween must be a [lo, hi] pair of numbers");
            }
            b.scoreBetween(new double[] {
                    ((Number) l.get(0)).doubleValue(),
                    ((Number) l.get(1)).doubleValue() });
            set++;
        }
        Object def = spec.get("default");
        if (def != null) {
            if (!(def instanceof Boolean dv) || !dv) {
                throw new IllegalStateException(
                        trail + ".default must be the literal 'true'");
            }
            b.defaultMatch(true);
            set++;
        }
        if (set != 1) {
            throw new IllegalStateException(
                    trail + ": exactly one of scoreAtLeast/scoreBelow/scoreBetween/"
                            + "default must be set (got " + set + ")");
        }
        return b.build();
    }

    private static @Nullable DeciderSpec parseDecider(@Nullable Object raw, String trail) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> m)) {
            throw new IllegalStateException(trail + ".decider must be a map");
        }
        Map<String, Object> spec = toStringMap(m);
        String storeAs = requireString(spec, "storeAs", trail + ".decider");
        List<String> options = asStringList(spec.get("options"));
        if (options.isEmpty()) {
            options = new ArrayList<>(List.of("yes", "no"));
        }
        Object casesRaw = spec.get("cases");
        if (!(casesRaw instanceof List<?> caseList) || caseList.isEmpty()) {
            throw new IllegalStateException(
                    trail + ".decider.cases must be a non-empty list");
        }
        List<DeciderCase> cases = new ArrayList<>(caseList.size());
        for (int i = 0; i < caseList.size(); i++) {
            Object c = caseList.get(i);
            if (!(c instanceof Map<?, ?> cm)) {
                throw new IllegalStateException(
                        trail + ".decider.cases[" + i + "] is not a map");
            }
            Map<String, Object> cs = toStringMap(cm);
            String when = requireDeciderWhen(cs, trail + ".decider.cases[" + i + "]");
            if (!options.contains(when.toLowerCase())
                    && !options.contains(when)) {
                throw new IllegalStateException(
                        trail + ".decider.cases[" + i + "].when='" + when
                                + "' must be one of options=" + options);
            }
            Object doRaw = cs.get("do");
            if (!(doRaw instanceof List<?> doList)) {
                throw new IllegalStateException(
                        trail + ".decider.cases[" + i + "].do must be a list");
            }
            cases.add(DeciderCase.builder()
                    .when(when)
                    .doActions(parseActionList(doList, trail + ".decider.cases[" + i + "].do"))
                    .build());
        }
        Integer maxCorrections = optInt(spec.get("maxCorrections"));
        if (maxCorrections != null && maxCorrections < 0) {
            throw new IllegalStateException(
                    trail + ".decider.maxCorrections must be >= 0");
        }
        return DeciderSpec.builder()
                .options(options)
                .storeAs(storeAs)
                .cases(cases)
                .maxCorrections(maxCorrections == null ? 2 : maxCorrections)
                .build();
    }

    private static List<BranchAction> parseActionList(List<?> raw, String trail) {
        List<BranchAction> out = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            Object item = raw.get(i);
            if (!(item instanceof Map<?, ?> mm)) {
                throw new IllegalStateException(
                        trail + "[" + i + "] is not a map (each action is a single-key map)");
            }
            Map<String, Object> entry = toStringMap(mm);
            if (entry.size() != 1) {
                throw new IllegalStateException(
                        trail + "[" + i + "] must have exactly one key (action name)");
            }
            Map.Entry<String, Object> e = entry.entrySet().iterator().next();
            BranchAction action = parseSingleAction(e.getKey(), e.getValue(),
                    trail + "[" + i + "]." + e.getKey());
            if (action.terminal() && i < raw.size() - 1) {
                throw new IllegalStateException(
                        trail + "[" + i + "].'" + e.getKey()
                                + "' is terminal — actions after it are unreachable");
            }
            out.add(action);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static BranchAction parseSingleAction(String key, @Nullable Object value, String trail) {
        return switch (key) {
            case "setFlag" -> {
                if (value instanceof String s && !s.isBlank()) {
                    yield new BranchAction.SetFlag(s, Boolean.TRUE);
                }
                if (value instanceof Map<?, ?> m && m.size() == 1) {
                    Map.Entry<?, ?> e = m.entrySet().iterator().next();
                    yield new BranchAction.SetFlag(String.valueOf(e.getKey()), e.getValue());
                }
                throw new IllegalStateException(
                        trail + " must be 'setFlag: name' or 'setFlag: { name: value }'");
            }
            case "setFlags" -> {
                List<String> names = asStringList(value);
                if (names.isEmpty()) {
                    throw new IllegalStateException(trail + " must be a non-empty list of flag names");
                }
                yield new BranchAction.SetFlags(names);
            }
            case "notifyParent" -> {
                if (!(value instanceof Map<?, ?> m)) {
                    throw new IllegalStateException(trail + " must be a map with 'type' (and optional 'summary')");
                }
                Map<String, Object> nm = toStringMap(m);
                String type = requireString(nm, "type", trail);
                yield new BranchAction.NotifyParent(type, optString(nm.get("summary")));
            }
            case "escalateTo" -> {
                if (value instanceof String s && !s.isBlank()) {
                    yield new BranchAction.EscalateTo(s);
                }
                if (value instanceof Map<?, ?> m) {
                    Map<String, Object> em = toStringMap(m);
                    String strat = requireString(em, "strategy", trail);
                    yield new BranchAction.EscalateTo(strat, toStringMap(em.get("params")));
                }
                throw new IllegalStateException(
                        trail + " must be 'escalateTo: <strategy>' or 'escalateTo: { strategy: …, params: … }'");
            }
            case "jumpToPhase" -> {
                if (!(value instanceof String s) || s.isBlank()) {
                    throw new IllegalStateException(trail + " must be a non-empty phase name");
                }
                yield new BranchAction.JumpToPhase(s);
            }
            case "pause" -> {
                String reason = null;
                if (value instanceof String s && !s.isBlank()) {
                    reason = s;
                } else if (value instanceof Map<?, ?> m) {
                    reason = optString(toStringMap(m).get("reason"));
                }
                yield new BranchAction.Pause(reason);
            }
            case "exitLoop" -> new BranchAction.ExitLoop(parseExitOutcome(value, trail));
            case "exitStrategy" -> new BranchAction.ExitStrategy(parseExitOutcome(value, trail));
            case "doc_create_text" -> {
                if (!(value instanceof Map<?, ?> m)) {
                    throw new IllegalStateException(
                            trail + " must be a map with 'path' and 'content'");
                }
                Map<String, Object> dm = toStringMap(m);
                yield new BranchAction.DocCreateText(
                        requireString(dm, "path", trail),
                        requireString(dm, "content", trail),
                        optString(dm.get("title")),
                        asStringList(dm.get("tags")),
                        optBool(dm.get("overwrite"), true));
            }
            case "doc_create_kind" -> {
                if (!(value instanceof Map<?, ?> m)) {
                    throw new IllegalStateException(
                            trail + " must be a map with 'path' and 'kind'");
                }
                Map<String, Object> dm = toStringMap(m);
                List<Map<String, Object>> items = null;
                if (dm.get("items") instanceof List<?> il) {
                    items = new ArrayList<>();
                    for (Object it : il) {
                        if (it instanceof Map<?, ?> im) items.add(toStringMap(im));
                    }
                }
                yield new BranchAction.DocCreateKind(
                        requireString(dm, "path", trail),
                        requireString(dm, "kind", trail),
                        optString(dm.get("title")),
                        asStringList(dm.get("tags")),
                        items,
                        optString(dm.get("itemsFromOutput")),
                        optBool(dm.get("overwrite"), true));
            }
            case "list_append" -> {
                if (!(value instanceof Map<?, ?> m)) {
                    throw new IllegalStateException(
                            trail + " must be a map with 'path' and 'text'");
                }
                Map<String, Object> dm = toStringMap(m);
                yield new BranchAction.ListAppend(
                        requireString(dm, "path", trail),
                        requireString(dm, "text", trail));
            }
            case "doc_concat" -> {
                if (!(value instanceof Map<?, ?> m)) {
                    throw new IllegalStateException(
                            trail + " must be a map with 'sources' and 'target'");
                }
                Map<String, Object> dm = toStringMap(m);
                List<String> sources = asStringList(dm.get("sources"));
                if (sources.isEmpty()) {
                    throw new IllegalStateException(
                            trail + ".sources must be a non-empty list");
                }
                yield new BranchAction.DocConcat(
                        sources,
                        requireString(dm, "target", trail),
                        optString(dm.get("separator")),
                        optString(dm.get("header")),
                        optString(dm.get("footer")),
                        optString(dm.get("title")));
            }
            case "inbox_post" -> {
                if (!(value instanceof Map<?, ?> m)) {
                    throw new IllegalStateException(
                            trail + " must be a map with 'type' and 'title'");
                }
                Map<String, Object> dm = toStringMap(m);
                yield new BranchAction.InboxPost(
                        requireString(dm, "type", trail),
                        requireString(dm, "title", trail),
                        optString(dm.get("body")),
                        optString(dm.get("criticality")));
            }
            default -> throw new IllegalStateException(
                    trail + " unknown action '" + key + "'");
        };
    }

    private static BranchAction.ExitOutcome parseExitOutcome(@Nullable Object value, String trail) {
        if (value == null) return BranchAction.ExitOutcome.OK;
        if (value instanceof String s) {
            return switch (s.trim().toLowerCase()) {
                case "ok" -> BranchAction.ExitOutcome.OK;
                case "fail" -> BranchAction.ExitOutcome.FAIL;
                default -> throw new IllegalStateException(
                        trail + " must be 'ok' or 'fail' (got '" + s + "')");
            };
        }
        throw new IllegalStateException(trail + " must be a string ('ok' or 'fail')");
    }

    /**
     * Resolve a decider {@code when:} value to a string. SnakeYAML 1.x
     * parses unquoted {@code yes} / {@code no} (and {@code on} / {@code off})
     * to booleans — so {@code when: yes} arrives here as
     * {@link Boolean#TRUE}. Convert booleans back to {@code "yes"} /
     * {@code "no"} so authors can write the natural form without
     * remembering to quote.
     */
    private static String requireDeciderWhen(Map<String, Object> spec, String trail) {
        Object raw = spec.get("when");
        if (raw instanceof String s && !s.isBlank()) return s;
        if (raw instanceof Boolean b) return b ? "yes" : "no";
        throw new IllegalStateException(
                trail + " missing required string 'when'");
    }

    private static @Nullable Integer optInt(@Nullable Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number n) return n.intValue();
        throw new IllegalStateException("expected integer, got " + raw.getClass().getSimpleName());
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

    private static boolean optBool(@Nullable Object raw, boolean fallback) {
        if (raw instanceof Boolean b) return b;
        if (raw instanceof String s) {
            if ("true".equalsIgnoreCase(s.trim())) return true;
            if ("false".equalsIgnoreCase(s.trim())) return false;
        }
        return fallback;
    }
}
