package de.mhus.vance.brain.slartibartfast.phases;

import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.ArchitectStatus;
import de.mhus.vance.api.slartibartfast.OutputSchemaType;
import de.mhus.vance.api.slartibartfast.PhaseIteration;
import de.mhus.vance.api.slartibartfast.RecipeDraft;
import de.mhus.vance.api.slartibartfast.RecoveryRequest;
import de.mhus.vance.api.slartibartfast.Subgoal;
import de.mhus.vance.api.slartibartfast.ValidationCheck;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.vogon.StrategyResolver;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * VALIDATING phase — hard validator gate after PROPOSING.
 * Verifies that the {@link RecipeDraft} is parseable as a Vogon
 * recipe, that every justification reference resolves to an
 * existing subgoal, and that the embedded
 * {@code params.strategyPlanYaml} survives
 * {@link StrategyResolver#parseStrategy} without throwing.
 *
 * <p>On failure: sets {@link ArchitectState#setPendingRecovery}
 * pointing back at PROPOSING with a corrective hint. Same
 * recovery contract as BindingPhase. Engine handles the rollback
 * + escalation logic.
 *
 * <p>Pure logic — no LLM, no I/O beyond the YAML parser.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ValidatingPhase {

    public static final String RULE_RECIPE_PRESENT =
            "proposed-recipe-present";
    public static final String RULE_YAML_PARSES =
            "recipe-yaml-parses";
    public static final String RULE_RECIPE_SHAPE =
            "recipe-has-name-engine";
    public static final String RULE_VOGON_STRATEGY_PARSES =
            "embedded-strategy-yaml-parses";
    public static final String RULE_JUSTIFICATION_RESOLVES =
            "justification-subgoal-id-resolves";
    public static final String RULE_SCHEMA_TYPE_SUPPORTED =
            "outputSchemaType-supported";

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

        List<ValidationCheck> report = new ArrayList<>();
        ValidationCheck firstFail = null;

        // 1. SchemaType supported (M4.2: only VOGON_STRATEGY).
        if (state.getOutputSchemaType() != OutputSchemaType.VOGON_STRATEGY) {
            ValidationCheck v = ValidationCheck.builder()
                    .rule(RULE_SCHEMA_TYPE_SUPPORTED)
                    .passed(false)
                    .offendingId(null)
                    .message("outputSchemaType "
                            + state.getOutputSchemaType()
                            + " not supported by current PROPOSING/VALIDATING "
                            + "(only VOGON_STRATEGY in v1)")
                    .build();
            report.add(v);
            firstFail = v;
        }

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

        // 3. Recipe has the expected shape: name + engine.
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
            } else if (!"vogon".equals(((String) engine).trim())) {
                ValidationCheck v = ValidationCheck.builder()
                        .rule(RULE_RECIPE_SHAPE).passed(false)
                        .message("recipe yaml engine='" + engine
                                + "' but VOGON_STRATEGY schemaType "
                                + "requires engine='vogon'")
                        .build();
                report.add(v);
                firstFail = v;
            } else {
                report.add(ValidationCheck.builder()
                        .rule(RULE_RECIPE_SHAPE).passed(true)
                        .message("recipe has name + engine: vogon").build());
            }
        }

        // 4. Embedded strategyPlanYaml parses via Vogon's resolver.
        if (firstFail == null && recipeMap != null) {
            Object params = recipeMap.get("params");
            if (!(params instanceof Map<?, ?> pm)) {
                ValidationCheck v = ValidationCheck.builder()
                        .rule(RULE_VOGON_STRATEGY_PARSES).passed(false)
                        .message("recipe yaml missing 'params' map")
                        .build();
                report.add(v);
                firstFail = v;
            } else {
                Object spy = ((Map<String, Object>) pm).get("strategyPlanYaml");
                if (!(spy instanceof String spyStr) || spyStr.isBlank()) {
                    ValidationCheck v = ValidationCheck.builder()
                            .rule(RULE_VOGON_STRATEGY_PARSES).passed(false)
                            .message("recipe yaml missing "
                                    + "'params.strategyPlanYaml' string")
                            .build();
                    report.add(v);
                    firstFail = v;
                } else {
                    try {
                        StrategyResolver.parseStrategy(spyStr,
                                "slartibartfast/" + draft.getName());
                        report.add(ValidationCheck.builder()
                                .rule(RULE_VOGON_STRATEGY_PARSES).passed(true)
                                .message("strategyPlanYaml parses cleanly")
                                .build());
                    } catch (RuntimeException e) {
                        ValidationCheck v = ValidationCheck.builder()
                                .rule(RULE_VOGON_STRATEGY_PARSES).passed(false)
                                .message("StrategyResolver rejected "
                                        + "strategyPlanYaml: " + e.getMessage())
                                .build();
                        report.add(v);
                        firstFail = v;
                    }
                }
            }
        }

        // 5. Justification refs resolve to subgoal ids.
        if (firstFail == null) {
            Set<String> sgIds = new HashSet<>();
            for (Subgoal sg : state.getSubgoals()) sgIds.add(sg.getId());
            for (Map.Entry<String, String> e :
                    draft.getJustifications().entrySet()) {
                String sgId = e.getValue();
                if (!sgIds.contains(sgId)) {
                    ValidationCheck v = ValidationCheck.builder()
                            .rule(RULE_JUSTIFICATION_RESOLVES)
                            .passed(false)
                            .offendingId(e.getKey())
                            .message("justification '" + e.getKey()
                                    + "' → '" + sgId
                                    + "' references non-existent subgoal")
                            .build();
                    report.add(v);
                    if (firstFail == null) firstFail = v;
                }
            }
            if (firstFail == null) {
                report.add(ValidationCheck.builder()
                        .rule(RULE_JUSTIFICATION_RESOLVES).passed(true)
                        .message(draft.getJustifications().size()
                                + " justification(s) all resolve to subgoals")
                        .build());
            }
        }

        // 6. Recipe-present check (defensive — already gated above).
        report.add(0, ValidationCheck.builder()
                .rule(RULE_RECIPE_PRESENT).passed(true)
                .message("RecipeDraft present").build());

        state.setValidationReport(report);

        if (firstFail != null) {
            String hint = buildHint(report);
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

    private static String buildHint(List<ValidationCheck> report) {
        StringBuilder sb = new StringBuilder();
        sb.append("Die VALIDATING-Phase hat das vorige Recipe abgelehnt. ")
                .append("Verstöße — addressiere JEDEN:\n");
        int shown = 0;
        for (ValidationCheck v : report) {
            if (v.isPassed()) continue;
            if (shown >= 5) break;
            sb.append("- [").append(v.getRule()).append("] ")
                    .append(v.getMessage()).append("\n");
            shown++;
        }
        sb.append("\nLiefere ein korrigiertes Recipe-YAML als JSON-Objekt ")
                .append("mit gültigem name, engine: vogon, ")
                .append("params.strategyPlanYaml (parseable von Vogon), und ")
                .append("justifications die alle auf existierende sg-ids ")
                .append("zeigen.");
        return sb.toString();
    }

    private static long countFailed(List<ValidationCheck> report) {
        return report.stream().filter(v -> !v.isPassed()).count();
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
