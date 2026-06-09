package de.mhus.vance.brain.slartibartfast.phases;

import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.ArchitectStatus;
import de.mhus.vance.api.slartibartfast.Criterion;
import de.mhus.vance.api.slartibartfast.OutputSchemaType;
import de.mhus.vance.api.slartibartfast.PhaseIteration;
import de.mhus.vance.api.slartibartfast.RecipeDraft;
import de.mhus.vance.api.slartibartfast.RecoveryRequest;
import de.mhus.vance.api.slartibartfast.Subgoal;
import de.mhus.vance.api.slartibartfast.ValidationCheck;
import de.mhus.vance.brain.slartibartfast.architect.SchemaArchitect;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * VALIDATING phase — hard validator gate after PROPOSING.
 * Verifies that the {@link RecipeDraft} parses as YAML with the
 * expected shape (name + engine), delegates schema-specific
 * structural validation to the registered
 * {@link SchemaArchitect}, and finally checks justification
 * references against the subgoal list and path-criteria against
 * the recipe's persistence phases.
 *
 * <p>On failure: sets {@link ArchitectState#setPendingRecovery}
 * pointing back at PROPOSING with a corrective hint composed of
 * the generic header + the architect's schema-specific tail.
 *
 * <p>Pure logic — no LLM, no I/O beyond the YAML parser.
 */
@Component
@Slf4j
public class ValidatingPhase {

    public static final String RULE_RECIPE_PRESENT =
            "proposed-recipe-present";
    public static final String RULE_YAML_PARSES =
            "recipe-yaml-parses";
    public static final String RULE_RECIPE_SHAPE =
            "recipe-has-name-engine";
    public static final String RULE_JUSTIFICATION_RESOLVES =
            "justification-subgoal-id-resolves";
    public static final String RULE_SCHEMA_TYPE_SUPPORTED =
            "outputSchemaType-supported";
    /**
     * Every acceptance criterion that names a file-path must be
     * backed by at least one recipe phase whose {@code workerInput}
     * contains both the {@code doc_create} tool name and the path
     * itself. Catches Slart's failure mode where the generated
     * Vogon recipe runs all content phases as in-chat drafts and
     * never persists to the kit-declared OUTPUT path (the recurring
     * symptom across yesterday's and today's runs).
     */
    public static final String RULE_PATH_OUTPUTS_PERSISTED =
            "path-criteria-have-doc-write-phase";

    /** The canonical (and only) write-tool name that satisfies
     *  RULE_PATH_OUTPUTS_PERSISTED. */
    private static final java.util.List<String> WRITE_TOOL_NAMES =
            java.util.List.of("doc_create");

    /** Matches a path inside a criterion text, same shape as
     *  {@link de.mhus.vance.brain.slartibartfast.PathCriteriaLifter#PATH_PATTERN}
     *  but slimmer: VALIDATING only cares about extracting the path
     *  back out of a previously-lifted criterion. The lifter's
     *  criterion text is stable ("…persist its output at `<path>`
     *  via doc_create."), so a back-tick capture is enough.
     */
    private static final java.util.regex.Pattern CRITERION_PATH_PATTERN =
            java.util.regex.Pattern.compile(
                    "`((?:[A-Za-z0-9_][A-Za-z0-9_.-]*/)+"
                            + "[A-Za-z0-9_][A-Za-z0-9_.-]*"
                            + "\\.(?:md|markdown|txt|yaml|yml|json|csv|pdf))`");

    private final Map<OutputSchemaType, SchemaArchitect> architects;

    public ValidatingPhase(List<SchemaArchitect> schemaArchitects) {
        Map<OutputSchemaType, SchemaArchitect> map = new EnumMap<>(OutputSchemaType.class);
        for (SchemaArchitect a : schemaArchitects) {
            SchemaArchitect existing = map.put(a.type(), a);
            if (existing != null) {
                throw new IllegalStateException(
                        "Duplicate SchemaArchitect beans for "
                                + a.type() + ": "
                                + existing.getClass().getName()
                                + " and " + a.getClass().getName());
            }
        }
        this.architects = Map.copyOf(map);
    }

    public void execute(
            ArchitectState state,
            ThinkProcessDocument process,
            ThinkEngineContext ctx) {

        RecipeDraft draft = state.getProposedRecipe();
        if (draft == null) {
            state.setFailureReason("VALIDATING entered without a "
                    + "proposedRecipe — PROPOSING must run first");
            return;
        }
        SchemaArchitect architect = architects.get(state.getOutputSchemaType());
        if (architect == null) {
            state.setFailureReason("VALIDATING has no SchemaArchitect "
                    + "bean for " + state.getOutputSchemaType());
            return;
        }

        List<ValidationCheck> report = new ArrayList<>();
        ValidationCheck firstFail = null;

        // 1. SchemaType supported — resolved via the architect map
        //    above; missing entries already failed out.

        // 2. YAML parses at all.
        Map<String, Object> recipeMap = null;
        if (firstFail == null) {
            try {
                Object loaded = new Yaml().load(draft.getYaml());
                if (!(loaded instanceof Map<?, ?> m)) {
                    ValidationCheck v = ValidationCheck.builder()
                            .rule(RULE_YAML_PARSES)
                            .passed(false)
                            .message("recipe yaml top-level is not a mapping "
                                    + "(got " + (loaded == null
                                            ? "null" : loaded.getClass().getSimpleName())
                                    + ")")
                            .build();
                    report.add(v);
                    firstFail = v;
                } else {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cast = (Map<String, Object>) m;
                    recipeMap = cast;
                    report.add(ValidationCheck.builder()
                            .rule(RULE_YAML_PARSES).passed(true)
                            .message("recipe yaml is a valid mapping").build());
                }
            } catch (RuntimeException e) {
                ValidationCheck v = ValidationCheck.builder()
                        .rule(RULE_YAML_PARSES)
                        .passed(false)
                        .message("recipe yaml parse error: " + e.getMessage())
                        .build();
                report.add(v);
                firstFail = v;
            }
        }

        // 3. Recipe has the expected shape: name + matching engine.
        String expectedEngine = architect.expectedEngineName();
        if (firstFail == null && recipeMap != null) {
            Object name = recipeMap.get("name");
            Object engine = recipeMap.get("engine");
            if (!(name instanceof String n) || n.isBlank()) {
                ValidationCheck v = ValidationCheck.builder()
                        .rule(RULE_RECIPE_SHAPE).passed(false)
                        .message("recipe yaml missing top-level 'name' string")
                        .build();
                report.add(v);
                firstFail = v;
            } else if (!(engine instanceof String e) || e.isBlank()) {
                ValidationCheck v = ValidationCheck.builder()
                        .rule(RULE_RECIPE_SHAPE).passed(false)
                        .message("recipe yaml missing top-level 'engine' string")
                        .build();
                report.add(v);
                firstFail = v;
            } else if (!expectedEngine.equals(((String) engine).trim())) {
                ValidationCheck v = ValidationCheck.builder()
                        .rule(RULE_RECIPE_SHAPE).passed(false)
                        .message("recipe yaml engine='" + engine
                                + "' but " + state.getOutputSchemaType()
                                + " schemaType requires engine='"
                                + expectedEngine + "'")
                        .build();
                report.add(v);
                firstFail = v;
            } else {
                report.add(ValidationCheck.builder()
                        .rule(RULE_RECIPE_SHAPE).passed(true)
                        .message("recipe has name + engine: " + expectedEngine)
                        .build());
            }
        }

        // 4. Schema-specific shape validation — delegated to the
        //    architect bean. Each bean knows what its YAML shape
        //    should look like and contributes ValidationCheck
        //    entries to the report. The first failing check (if
        //    any) drives the recovery loop.
        if (firstFail == null && recipeMap != null) {
            ValidationCheck archFail = architect.validateDraftShape(
                    draft, recipeMap, process, report);
            if (archFail != null) firstFail = archFail;
        }

        // 5. Justification refs resolve to subgoal ids.
        //    Empirically the LLM occasionally emits a comma-
        //    separated value ("sg1, sg2, sg3") when one constraint
        //    covers several subgoals — JSON forbids duplicate keys
        //    so it can't list multiple entries. We split + check
        //    each part rather than reject the whole entry on a
        //    purely formatting choice. Every part still has to be
        //    a real sg-id; otherwise the entry is rejected with a
        //    pointer to the unresolved name(s).
        if (firstFail == null) {
            Set<String> sgIds = new HashSet<>();
            for (Subgoal sg : state.getSubgoals()) sgIds.add(sg.getId());
            int totalRefs = 0;
            for (Map.Entry<String, String> e :
                    draft.getJustifications().entrySet()) {
                String raw = e.getValue();
                List<String> parts = splitSgIdList(raw);
                List<String> unknown = new ArrayList<>();
                for (String part : parts) {
                    if (!sgIds.contains(part)) unknown.add(part);
                }
                if (parts.isEmpty() || !unknown.isEmpty()) {
                    String missing = parts.isEmpty()
                            ? raw
                            : String.join(", ", unknown);
                    ValidationCheck v = ValidationCheck.builder()
                            .rule(RULE_JUSTIFICATION_RESOLVES)
                            .passed(false)
                            .offendingId(e.getKey())
                            .message("justification '" + e.getKey()
                                    + "' → '" + raw
                                    + "' references non-existent subgoal: "
                                    + missing)
                            .build();
                    report.add(v);
                    if (firstFail == null) firstFail = v;
                } else {
                    totalRefs += parts.size();
                }
            }
            if (firstFail == null) {
                report.add(ValidationCheck.builder()
                        .rule(RULE_JUSTIFICATION_RESOLVES).passed(true)
                        .message(draft.getJustifications().size()
                                + " justification(s) all resolve to subgoals "
                                + "(" + totalRefs + " sg-id ref(s) checked)")
                        .build());
            }
        }

        // 6. Recipe-present check (defensive — already gated above).
        report.add(0, ValidationCheck.builder()
                .rule(RULE_RECIPE_PRESENT).passed(true)
                .message("RecipeDraft present").build());

        // 7. Path-criteria → persistence-phase check. Each
        //    acceptance criterion that names a file-path (lifted by
        //    PathCriteriaLifter from OUTPUT.md evidence claims, or
        //    user-stated) must be backed by at least one recipe
        //    phase whose workerInput contains both `doc_create`
        //    and the path literal. Without this, Slart's generated
        //    recipes end with chat-only review phases and the kit-
        //    declared OUTPUT folder stays empty.
        //
        //    Architects whose recipes write outputs through spawned
        //    sub-processes (Zaphod heads/synthesizer) rather than
        //    direct tool calls in the Slart-emitted YAML opt out via
        //    {@link SchemaArchitect#wantsPathPersistenceCheck()};
        //    the substring scan would always fail for them.
        if (architect.wantsPathPersistenceCheck()) {
            ValidationCheck pathCheck = checkPathPersistence(draft, state);
            if (pathCheck != null) {
                report.add(pathCheck);
                if (!pathCheck.isPassed() && firstFail == null) {
                    firstFail = pathCheck;
                }
            }
        }

        state.setValidationReport(report);

        if (firstFail != null) {
            String hint = buildHint(report, state, process, architect);
            state.setPendingRecovery(RecoveryRequest.builder()
                    .fromPhase(ArchitectStatus.VALIDATING)
                    .toPhase(ArchitectStatus.PROPOSING)
                    .reason(firstFail.getRule())
                    .hint(hint)
                    .offendingId(firstFail.getOffendingId())
                    .build());

            appendIteration(state,
                    "draft '" + draft.getName() + "', "
                            + draft.getJustifications().size()
                            + " justifications",
                    "FAILED — " + countFailed(report) + " violation(s); "
                            + "rollback to PROPOSING",
                    PhaseIteration.IterationOutcome.REQUESTED_RECOVERY);

            log.info("Slartibartfast id='{}' VALIDATING failed — {} violations, "
                            + "requesting PROPOSING re-run",
                    process.getId(), countFailed(report));
        } else {
            appendIteration(state,
                    "draft '" + draft.getName() + "'",
                    "passed — yaml + strategy + " + draft.getJustifications().size()
                            + " justifications all valid",
                    PhaseIteration.IterationOutcome.PASSED);
        }
    }

    // ──────────────────── Helpers ────────────────────

    private String buildHint(
            List<ValidationCheck> report, ArchitectState state,
            ThinkProcessDocument process, SchemaArchitect architect) {
        StringBuilder sb = new StringBuilder();
        sb.append("VALIDATING rejected the previous recipe. "
                + "Violations — address EVERY one:\n");
        int shown = 0;
        for (ValidationCheck v : report) {
            if (v.isPassed()) continue;
            if (shown >= 5) break;
            sb.append("- [").append(v.getRule()).append("] ")
                    .append(v.getMessage()).append("\n");
            shown++;
        }
        // Echo the existing sg-ids verbatim so the LLM can't drift to
        // hallucinated names like 'sg9' when only sg1..sgN exist.
        sb.append("\nValid sg-ids (use ONLY these as justification "
                + "values): ");
        boolean first = true;
        for (Subgoal sg : state.getSubgoals()) {
            if (!first) sb.append(", ");
            sb.append(sg.getId());
            first = false;
        }
        sb.append(".\n");

        // Schema-specific tail (recipe-name inventory for Marvin,
        // engine + shape instructions for everyone) is owned by
        // the architect bean.
        sb.append(architect.recoveryHintTail(process));
        return sb.toString();
    }

    /**
     * Validates that every entry in {@code params.allowedSubTaskRecipes}
     * and {@code params.recipesOnlyViaExpand} is a real project
     * recipe — both fields hold recipe names (NOT engine names), so
     * a name that doesn't resolve via the recipe-cascade is by
     * definition wrong (whether the LLM hallucinated it freshly or
     * mis-typed an engine label is irrelevant). Also catches
     * duplicate entries.
     */

    /**
     * Splits a justification value into individual sg-id references.
     * Tolerates a single value ("sg1") or a comma-separated list
     * ("sg1, sg2, sg3"). Empty fragments and surrounding whitespace
     * are dropped; the order of the result follows the input.
     */
    private static List<String> splitSgIdList(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }


    private static long countFailed(List<ValidationCheck> report) {
        return report.stream().filter(v -> !v.isPassed()).count();
    }

    /**
     * For every acceptance criterion that names a file-path, check
     * that the recipe YAML has at least one phase whose
     * {@code workerInput} contains BOTH the literal string
     * {@code doc_create} AND the path itself. Returns null
     * when the recipe has no path-criteria at all (nothing to
     * check); a passing {@link ValidationCheck} when every path is
     * covered; a failing one with an offending-path message when
     * any path is missing.
     *
     * <p>Substring matching on the YAML — coarse but works because
     * Slart's emitted recipes carry the workerInput as plain text
     * blocks and {@code doc_create} is a tool name that doesn't
     * occur in normal prose. False positives are tolerable here;
     * a false negative would silently let an unpersisted recipe
     * through, which is exactly the failure mode we're trying to
     * close.
     */
    private static @org.jspecify.annotations.Nullable ValidationCheck
    checkPathPersistence(
            de.mhus.vance.api.slartibartfast.RecipeDraft draft,
            ArchitectState state) {
        if (draft == null) return null;
        String yaml = draft.getYaml() == null ? "" : draft.getYaml();
        // Collect every path mentioned in acceptanceCriteria text.
        java.util.LinkedHashSet<String> requiredPaths = new java.util.LinkedHashSet<>();
        for (Criterion c : state.getAcceptanceCriteria()) {
            String t = c.getText();
            if (t == null) continue;
            java.util.regex.Matcher m = CRITERION_PATH_PATTERN.matcher(t);
            while (m.find()) requiredPaths.add(m.group(1));
        }
        if (requiredPaths.isEmpty()) {
            return null;  // nothing to enforce, skip the check entirely
        }

        // For each required path, the YAML must contain both a
        // recognised write-tool name AND the path string. Match on
        // substring — we can't trivially parse the strategyPlanYaml
        // here.
        boolean hasAnyWriteTool = false;
        for (String t : WRITE_TOOL_NAMES) {
            if (yaml.contains(t)) {
                hasAnyWriteTool = true;
                break;
            }
        }
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String path : requiredPaths) {
            boolean hasPath = yaml.contains(path);
            if (!hasAnyWriteTool || !hasPath) {
                missing.add(path);
            }
        }
        if (missing.isEmpty()) {
            return ValidationCheck.builder()
                    .rule(RULE_PATH_OUTPUTS_PERSISTED).passed(true)
                    .message(requiredPaths.size()
                            + " path criterion(s) all have backing "
                            + "doc_create phases in the recipe")
                    .build();
        }
        return ValidationCheck.builder()
                .rule(RULE_PATH_OUTPUTS_PERSISTED)
                .passed(false)
                .message("acceptance criteria require outputs at "
                        + missing.size() + " path(s) that no recipe "
                        + "phase persists: " + missing
                        + ". For Vogon strategies: add a phase whose "
                        + "workerInput calls doc_create with the "
                        + "literal path. "
                        + "For Marvin recipes: add a `postActions` "
                        + "block INSIDE the taskSpec of the node "
                        + "whose output should be persisted (typically "
                        + "the AGGREGATE child or the final WORKER) "
                        + "with `tool: doc_create` + `args.path` + "
                        + "`args.content`. "
                        + "Persist ONLY the user-requested artefacts — "
                        + "do NOT add postActions that save unrelated "
                        + "files (e.g. the recipe yaml itself or "
                        + "intermediate scratchpad data); those are "
                        + "the recipe's own definition, not the "
                        + "user's deliverable. "
                        + "Without it the project's output folder "
                        + "stays empty after the run.")
                .build();
    }

    private static void appendIteration(
            ArchitectState state,
            String inputSummary,
            String outputSummary,
            PhaseIteration.IterationOutcome outcome) {
        int attempt = (int) state.getIterations().stream()
                .filter(it -> it.getPhase() == ArchitectStatus.VALIDATING).count() + 1;
        List<PhaseIteration> log = new ArrayList<>(state.getIterations());
        log.add(PhaseIteration.builder()
                .iteration(attempt)
                .phase(ArchitectStatus.VALIDATING)
                .triggeredBy(attempt == 1 ? "initial" : "recovery")
                .inputSummary(inputSummary)
                .outputSummary(outputSummary)
                .outcome(outcome)
                .build());
        state.setIterations(log);
    }
}
