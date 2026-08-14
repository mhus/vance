package de.mhus.vance.brain.vogon;

import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.shared.magrathea.MagratheaParameterSpec;
import de.mhus.vance.shared.magrathea.MagratheaWorkflowLoader;
import de.mhus.vance.shared.magrathea.ResolvedMagratheaWorkflow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Turns what somebody asked for into the parameters a plan declared.
 *
 * <p>A worker is given its job as a message — "release version 1.0.0" — while a
 * plan wants named values. Without something in between, Vogon is the one worker
 * you cannot talk to: the task text arrives and goes nowhere, and the caller has
 * to write the same thing a second time as {@code params.goal}.
 *
 * <p><b>The model call is the last stage, not the first.</b> Explicit params
 * always win; a call happens only when required fields are still missing
 * afterwards and there is a text to read them out of. A precise caller pays
 * nothing, only a conversational one does.
 *
 * <p><b>Choosing the plan and filling it in are two stages.</b> When the plan
 * itself has to come out of the text, the parameters that matter are not known
 * until it is chosen — so a second call reads them, against the schema of the
 * plan that was picked. One call cannot do both: it would have to ask about
 * fields whose names depend on its own answer.
 *
 * <p><b>Nothing is guessed about which plan to run.</b> When the plan itself has
 * to come out of the text, the choice is an enum over the plans that actually
 * resolve — a hallucinated name that happens to exist would start the wrong
 * plan, and that is the one failure here that is silent.
 *
 * <p>This stage does not decide what the plan <em>does</em>. It decides what it
 * starts with, and is never consulted again — which is what keeps Vogon a runner
 * of written plans rather than an engine that makes them up.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VogonIntake {

    /** {@code params.intake} value that switches this stage off entirely. */
    public static final String INTAKE_NONE = "none";

    /** Recipe backing the extraction call — {@code internal: true}. */
    static final String INTAKE_RECIPE = "vogon-intake";

    /** Field the extractor uses when it has to name the plan itself. */
    static final String FIELD_PLAN = "plan";

    private final ObjectProvider<LightLlmService> lightLlmProvider;
    private final MagratheaWorkflowLoader workflowLoader;
    /** Datenhoheit: plan documents are read through the owning service. */
    private final de.mhus.vance.shared.document.DocumentService documentService;

    /**
     * What the intake produced, and where each part came from.
     *
     * <p>At most one of {@code planName} / {@code planPath} is set: a plan is
     * addressed either by name through the cascade, or by the document it
     * lives in. Both at once would be two answers to one question.
     */
    public record Outcome(
            @Nullable String planName,
            @Nullable String planPath,
            Map<String, Object> params,
            /** Keys that were read out of the task text rather than passed in. */
            Set<String> derivedKeys) {

        public Outcome {
            params = Map.copyOf(params);
            derivedKeys = Set.copyOf(derivedKeys);
        }

        public static Outcome of(@Nullable String planName, Map<String, Object> params) {
            return new Outcome(planName, null, params, Set.of());
        }

        /** True when the intake found neither a name nor a path. */
        public boolean hasNoPlan() {
            return (planName == null || planName.isBlank())
                    && (planPath == null || planPath.isBlank());
        }
    }

    /**
     * Everything the caller could be missing, as one question.
     *
     * @param plan       the plan, when it is already known
     * @param callerParams params passed explicitly at spawn — never overwritten
     * @param taskText   what the person asked for, or null
     * @param intakeMode {@code params.intake}; {@code none} disables the call
     */
    public Outcome resolve(
            String tenantId,
            String projectId,
            @Nullable ResolvedMagratheaWorkflow plan,
            @Nullable String planName,
            Map<String, Object> callerParams,
            @Nullable String taskText,
            @Nullable String intakeMode) {

        Outcome chosen = choosePlan(
                tenantId, projectId, plan, planName, callerParams, taskText, intakeMode);
        if (plan != null || chosen.hasNoPlan()) {
            // Either the plan was known all along — then its parameters were
            // already part of the one call — or it still is not known, and
            // there is nothing to ask parameters of.
            return chosen;
        }
        return withPlanParameters(tenantId, projectId, chosen, taskText);
    }

    /**
     * The first pass: which plan, and — when it was already known — its
     * missing parameters in the same call.
     */
    private Outcome choosePlan(
            String tenantId,
            String projectId,
            @Nullable ResolvedMagratheaWorkflow plan,
            @Nullable String planName,
            Map<String, Object> callerParams,
            @Nullable String taskText,
            @Nullable String intakeMode) {

        boolean needsPlan = plan == null;
        List<String> missing = needsPlan ? List.of() : missingRequired(plan, callerParams);

        // Nothing to work out: the caller was precise, or there is a plan and
        // it already has everything it asked for.
        if (!needsPlan && missing.isEmpty()) {
            return Outcome.of(planName, callerParams);
        }
        if (INTAKE_NONE.equalsIgnoreCase(String.valueOf(intakeMode))) {
            // Declared as never fed from prose — say so instead of guessing.
            return Outcome.of(planName, callerParams);
        }
        if (taskText == null || taskText.isBlank()) {
            // Caller said nothing; the start path reports what is missing.
            return Outcome.of(planName, callerParams);
        }

        // A path in the request answers the plan question outright. Asking a
        // model to copy something that is already written can only make it
        // less right, and it would cost a call to do so.
        String spokenPath = needsPlan ? pathIn(taskText) : null;
        if (spokenPath != null && missing.isEmpty()) {
            log.info("Vogon intake took the plan path '{}' straight from the request",
                    spokenPath);
            return new Outcome(null, spokenPath, callerParams, Set.of(FIELD_PLAN));
        }

        LightLlmService lightLlm = lightLlmProvider.getIfAvailable();
        if (lightLlm == null) {
            log.warn("Vogon intake skipped — no LightLlmService on this pod");
            return new Outcome(planName, spokenPath, callerParams,
                    spokenPath == null ? Set.of() : Set.of(FIELD_PLAN));
        }

        // Only the name case needs the list: a path is not chosen from it.
        boolean needsPlanName = needsPlan && spokenPath == null;
        List<String> candidates = needsPlanName ? resolvablePlans(tenantId, projectId) : List.of();
        if (needsPlanName && candidates.isEmpty()) {
            log.warn("Vogon intake cannot pick a plan — no plans resolve in {}/{}",
                    tenantId, projectId);
            return Outcome.of(null, callerParams);
        }

        Map<String, Object> extracted = callExtractor(
                tenantId, projectId, lightLlm, taskText,
                buildSchema(plan, missing, needsPlanName, candidates),
                candidates, planName, needsPlanName, missing);
        if (extracted == null) {
            return Outcome.of(planName, callerParams);
        }

        Map<String, Object> merged = new LinkedHashMap<>(callerParams);
        Set<String> derived = new LinkedHashSet<>();
        String resolvedPlan = planName;

        String resolvedPath = spokenPath;
        if (spokenPath != null) derived.add(FIELD_PLAN);

        for (Map.Entry<String, Object> e : extracted.entrySet()) {
            if (FIELD_PLAN.equals(e.getKey())) {
                if (needsPlanName && e.getValue() instanceof String s && !s.isBlank()) {
                    // Tolerated: a model that answers with a path anyway is
                    // right about the plan and wrong only about the field.
                    if (looksLikePath(s)) {
                        resolvedPath = s.trim();
                    } else {
                        resolvedPlan = s;
                    }
                    derived.add(FIELD_PLAN);
                }
                continue;
            }
            if (e.getValue() == null) continue;
            // Explicit beats derived, always.
            if (merged.containsKey(e.getKey())) continue;
            merged.put(e.getKey(), e.getValue());
            derived.add(e.getKey());
        }
        log.info("Vogon intake read {} from the task text (plan='{}', path='{}')",
                derived, resolvedPlan, resolvedPath);
        return new Outcome(resolvedPlan, resolvedPath, merged, derived);
    }

    /**
     * The second pass: now that the plan is known, read what <em>it</em>
     * declares out of the same request.
     *
     * <p>Two calls rather than one, because the question in the second
     * depends on the answer to the first: which parameters exist is a
     * property of the chosen plan, and a schema cannot ask about fields it
     * does not yet know the names of. Skipped entirely when the plan wants
     * nothing the caller did not already supply — which is the common case
     * for plans written to be started from a conversation, so the second
     * call is the exception, not the rule.
     *
     * <p>Without this, naming the plan in prose — the thing the bare
     * {@code vogon} recipe tells people to do — resolves the plan and then
     * fails the start on its first required parameter.
     */
    private Outcome withPlanParameters(
            String tenantId, String projectId, Outcome chosen, @Nullable String taskText) {

        String path = chosen.planPath();
        String name = chosen.planName();
        ResolvedMagratheaWorkflow plan = (path != null
                ? loadPlanFromPath(tenantId, projectId, path)
                : name == null
                        ? Optional.<ResolvedMagratheaWorkflow>empty()
                        : loadPlan(tenantId, projectId, name)).orElse(null);
        if (plan == null) {
            // Unreadable here means unstartable in a moment; the start path
            // produces the better error for it.
            return chosen;
        }
        List<String> missing = missingRequired(plan, chosen.params());
        if (missing.isEmpty()) {
            return chosen;
        }
        LightLlmService lightLlm = lightLlmProvider.getIfAvailable();
        if (lightLlm == null || taskText == null || taskText.isBlank()) {
            return chosen;
        }

        Map<String, Object> extracted = callExtractor(
                tenantId, projectId, lightLlm, taskText,
                buildSchema(plan, missing, /*needsPlan*/ false, List.of()),
                /*candidates*/ List.of(), plan.name(), /*needsPlanName*/ false, missing);
        if (extracted == null) {
            return chosen;
        }

        Map<String, Object> merged = new LinkedHashMap<>(chosen.params());
        Set<String> derived = new LinkedHashSet<>(chosen.derivedKeys());
        for (Map.Entry<String, Object> e : extracted.entrySet()) {
            if (FIELD_PLAN.equals(e.getKey()) || e.getValue() == null) continue;
            if (merged.containsKey(e.getKey())) continue;
            merged.put(e.getKey(), e.getValue());
            derived.add(e.getKey());
        }
        log.info("Vogon intake read {} for plan '{}' in a second pass", derived, plan.name());
        return new Outcome(chosen.planName(), chosen.planPath(), merged, derived);
    }

    /**
     * One extraction call. {@code null} when it could not be made — a
     * failed reading is not a failed run, and the start path reports the
     * fields that are still missing by name, which is the better error.
     */
    private @Nullable Map<String, Object> callExtractor(
            String tenantId,
            String projectId,
            LightLlmService lightLlm,
            String taskText,
            Map<String, Object> schema,
            List<String> candidates,
            @Nullable String planName,
            boolean needsPlanName,
            List<String> missing) {
        try {
            return lightLlm.callForJson(LightLlmRequest.builder()
                    .recipeName(INTAKE_RECIPE)
                    .userPrompt(taskText)
                    .schema(schema)
                    .pebbleVars(Map.of(
                            "plans", candidates,
                            "planName", planName == null ? "" : planName,
                            "needsPlan", needsPlanName,
                            "missing", missing))
                    .tenantId(tenantId)
                    .projectId(projectId)
                    .build());
        } catch (RuntimeException ex) {
            log.warn("Vogon intake could not read the task text: {}", ex.toString());
            return null;
        }
    }

    /**
     * Does this reference address a document rather than name a plan?
     *
     * <p>A slash or a YAML suffix is enough: plan names live in one flat
     * namespace under {@code _vance/workflows/} and never contain either.
     * The distinction matters because the two are resolved differently — a
     * name goes through the cascade (project before tenant), a path names
     * exactly one document, wherever it lies.
     */
    public static boolean looksLikePath(@Nullable String ref) {
        if (ref == null) return false;
        String s = ref.trim();
        return s.contains("/") || s.endsWith(".yaml") || s.endsWith(".yml");
    }

    /**
     * A document path somebody wrote into their request, if they did.
     *
     * <p>Found here rather than asked of a model: the path is already in the
     * text verbatim, and a model asked to copy it can only ever get it less
     * right. Punctuation that ends a sentence is trimmed, since "run
     * workflows/hello.yaml." is one word to a person and two to a parser.
     */
    static @Nullable String pathIn(@Nullable String text) {
        if (text == null) return null;
        for (String raw : text.split("\\s+")) {
            String token = trimSentencePunctuation(raw);
            if (token.length() > 1 && looksLikePath(token)) {
                return token;
            }
        }
        return null;
    }

    private static String trimSentencePunctuation(String raw) {
        String s = raw.trim();
        while (!s.isEmpty() && ".,;:!?\"')".indexOf(s.charAt(s.length() - 1)) >= 0) {
            s = s.substring(0, s.length() - 1);
        }
        while (!s.isEmpty() && "\"'(".indexOf(s.charAt(0)) >= 0) {
            s = s.substring(1);
        }
        return s;
    }

    /** Required parameters the caller has not supplied. */
    static List<String> missingRequired(
            ResolvedMagratheaWorkflow plan, Map<String, Object> callerParams) {
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, MagratheaParameterSpec> e : plan.parameters().entrySet()) {
            MagratheaParameterSpec spec = e.getValue();
            if (!spec.required()) continue;
            if (spec.defaultValue() != null) continue;
            if (callerParams.containsKey(e.getKey())) continue;
            missing.add(e.getKey());
        }
        return List.copyOf(missing);
    }

    /**
     * The extraction schema: the plan's own declared parameters, plus the plan
     * choice itself when that is still open.
     */
    private static Map<String, Object> buildSchema(
            @Nullable ResolvedMagratheaWorkflow plan,
            List<String> missing,
            boolean needsPlan,
            List<String> candidates) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        if (needsPlan) {
            properties.put(FIELD_PLAN, Map.of(
                    "type", "string",
                    "enum", candidates,
                    "description", "Which of these plans the request refers to."));
            required.add(FIELD_PLAN);
        }
        if (plan != null) {
            for (String key : missing) {
                MagratheaParameterSpec spec = plan.parameters().get(key);
                properties.put(key, Map.of(
                        "type", jsonType(spec == null ? "string" : spec.type()),
                        "description", "Value for the plan's '" + key + "' parameter."));
                required.add(key);
            }
        }
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", required);
    }

    /** The plan's declared type, in the words a JSON schema uses. */
    private static String jsonType(String declared) {
        return switch (declared == null ? "" : declared.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "int", "integer", "long" -> "integer";
            case "number", "float", "double" -> "number";
            case "bool", "boolean" -> "boolean";
            case "list", "array" -> "array";
            case "map", "object" -> "object";
            default -> "string";
        };
    }

    /** Plans that actually resolve here — the only names the extractor may pick. */
    private List<String> resolvablePlans(String tenantId, String projectId) {
        try {
            return workflowLoader.listAll(tenantId, projectId).stream()
                    .map(ResolvedMagratheaWorkflow::name)
                    .filter(n -> n != null && !n.isBlank())
                    .distinct()
                    .sorted()
                    .toList();
        } catch (RuntimeException ex) {
            log.warn("Vogon intake could not list plans: {}", ex.toString());
            return List.of();
        }
    }

    /** Load a plan by name, for the start path to validate against. */
    public Optional<ResolvedMagratheaWorkflow> loadPlan(
            String tenantId, String projectId, String name) {
        try {
            return workflowLoader.load(tenantId, projectId, name);
        } catch (RuntimeException ex) {
            log.debug("Vogon intake could not load plan '{}': {}", name, ex.toString());
            return Optional.empty();
        }
    }

    /**
     * Load a plan addressed by document path.
     *
     * <p>Read here only to see which parameters it declares — the run itself
     * re-reads and freezes the document, so this is a look, not a handover.
     */
    public Optional<ResolvedMagratheaWorkflow> loadPlanFromPath(
            String tenantId, String projectId, String path) {
        try {
            return documentService.findByPath(tenantId, projectId, path)
                    .map(doc -> MagratheaWorkflowLoader.parseYaml(
                            planNameFromPath(path), documentService.readContent(doc)));
        } catch (RuntimeException ex) {
            log.debug("Vogon intake could not read plan at '{}': {}", path, ex.toString());
            return Optional.empty();
        }
    }

    private static String planNameFromPath(String path) {
        String stem = path.substring(path.lastIndexOf('/') + 1);
        int dot = stem.lastIndexOf('.');
        return dot > 0 ? stem.substring(0, dot) : stem;
    }
}
