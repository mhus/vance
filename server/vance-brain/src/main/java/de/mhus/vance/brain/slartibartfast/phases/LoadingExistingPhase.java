package de.mhus.vance.brain.slartibartfast.phases;

import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.ArchitectStatus;
import de.mhus.vance.api.slartibartfast.OutputSchemaType;
import de.mhus.vance.api.slartibartfast.PhaseIteration;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * LOADING_EXISTING phase — deterministic (no LLM). Runs only
 * when {@link ArchitectState#getMode()} is
 * {@link de.mhus.vance.api.slartibartfast.ArchitectMode#EDIT}.
 * Loads the recipe identified by
 * {@link ArchitectState#getTargetRecipeName()} from the user
 * namespace (`recipes/_user/<name>.yaml`), parses it, detects
 * the {@link OutputSchemaType} from the `engine:` field, and
 * stashes the raw yaml plus parsed map on the state.
 *
 * <p>The detected schema overrides any engine-param-supplied
 * {@code outputSchemaType}. On EDIT the schema is a property of
 * the existing recipe — not of the user request.
 *
 * <p>Failure modes (all transition to FAILED with a clear
 * {@code failureReason}):
 * <ul>
 *   <li>{@code targetRecipeName} is null/blank — FRAMING-LLM
 *       didn't extract a name despite EDIT mode</li>
 *   <li>document at the resolved path doesn't exist</li>
 *   <li>YAML parse fails or top-level isn't a map</li>
 *   <li>{@code engine:} field missing or names a schema Slart
 *       doesn't author (e.g. {@code engine: ford})</li>
 * </ul>
 *
 * <p>See {@code planning/slart-as-project-architect.md} §D-4.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LoadingExistingPhase {

    /** Path prefix for user-namespace recipes — Slart's permanent
     *  write surface for named CREATE and all EDIT runs. */
    public static final String USER_PREFIX = "_vance/recipes/_user/";

    private final DocumentService documentService;

    public void execute(
            ArchitectState state,
            ThinkProcessDocument process,
            ThinkEngineContext ctx) {

        String name = state.getTargetRecipeName();
        if (name == null || name.isBlank()) {
            state.setFailureReason("LOADING_EXISTING entered without "
                    + "targetRecipeName — FRAMING must set it for EDIT mode");
            appendFailedIteration(state, "<missing name>",
                    "FAILED — no targetRecipeName");
            return;
        }

        String path = USER_PREFIX + name + ".yaml";

        Optional<DocumentDocument> doc = documentService.findByPath(
                process.getTenantId(), process.getProjectId(), path);
        if (doc.isEmpty()) {
            state.setFailureReason("Recipe '" + name + "' not found at "
                    + path + " — only user-namespace recipes are editable. "
                    + "Did you mean to CREATE this recipe?");
            appendFailedIteration(state, path,
                    "FAILED — document not found");
            return;
        }

        String yaml = doc.get().getInlineText();
        if (yaml == null || yaml.isBlank()) {
            state.setFailureReason("Existing recipe '" + name
                    + "' at " + path + " is empty");
            appendFailedIteration(state, path, "FAILED — empty document");
            return;
        }

        Map<String, Object> recipeMap;
        try {
            Object parsed = new Yaml().load(yaml);
            if (!(parsed instanceof Map<?, ?> m)) {
                state.setFailureReason("Existing recipe '" + name
                        + "' top-level YAML is not a map");
                appendFailedIteration(state, path,
                        "FAILED — top-level not a map");
                return;
            }
            recipeMap = toStringMap(m);
        } catch (RuntimeException e) {
            state.setFailureReason("Existing recipe '" + name
                    + "' yaml parse error: " + e.getMessage());
            appendFailedIteration(state, path,
                    "FAILED — yaml parse: " + e.getMessage());
            return;
        }

        Object engineRaw = recipeMap.get("engine");
        if (!(engineRaw instanceof String engineName) || engineName.isBlank()) {
            state.setFailureReason("Existing recipe '" + name
                    + "' missing top-level 'engine' field");
            appendFailedIteration(state, path,
                    "FAILED — no engine field");
            return;
        }

        OutputSchemaType detected;
        switch (engineName.trim().toLowerCase()) {
            case "vogon"  -> detected = OutputSchemaType.VOGON_STRATEGY;
            case "marvin" -> detected = OutputSchemaType.MARVIN_RECIPE;
            case "zaphod" -> detected = OutputSchemaType.ZAPHOD_RECIPE;
            default -> {
                state.setFailureReason("Slart can only edit architect "
                        + "recipes (vogon/marvin/zaphod). Recipe '" + name
                        + "' has engine='" + engineName + "' — edit "
                        + "directly in the document editor.");
                appendFailedIteration(state, path,
                        "FAILED — engine='" + engineName + "' not editable");
                return;
            }
        }

        state.setExistingRecipeYaml(yaml);
        state.setExistingRecipeMap(recipeMap);
        state.setOutputSchemaType(detected);

        log.info("Slartibartfast id='{}' LOADING_EXISTING loaded '{}' "
                        + "({} chars, engine={}, schema={})",
                process.getId(), path, yaml.length(), engineName, detected);

        appendPassedIteration(state, path,
                "loaded engine=" + engineName + ", schema=" + detected
                        + ", " + yaml.length() + " chars");
    }

    // ──────────────────── helpers ────────────────────

    private static void appendPassedIteration(
            ArchitectState state, String input, String output) {
        appendIteration(state, input, output,
                PhaseIteration.IterationOutcome.PASSED);
    }

    private static void appendFailedIteration(
            ArchitectState state, String input, String output) {
        appendIteration(state, input, output,
                PhaseIteration.IterationOutcome.FAILED);
    }

    private static void appendIteration(
            ArchitectState state, String input, String output,
            PhaseIteration.IterationOutcome outcome) {
        int attempt = (int) state.getIterations().stream()
                .filter(it -> it.getPhase() == ArchitectStatus.LOADING_EXISTING)
                .count() + 1;
        List<PhaseIteration> log = new ArrayList<>(state.getIterations());
        log.add(PhaseIteration.builder()
                .iteration(attempt)
                .phase(ArchitectStatus.LOADING_EXISTING)
                .triggeredBy("initial")
                .inputSummary(input)
                .outputSummary(output)
                .outcome(outcome)
                .build());
        state.setIterations(log);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toStringMap(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>(m.size());
        for (Map.Entry<?, ?> e : m.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }
}
