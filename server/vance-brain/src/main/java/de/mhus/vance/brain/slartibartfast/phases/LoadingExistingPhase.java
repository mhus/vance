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
 * LOADING_EXISTING phase — deterministic (no LLM). Runs after
 * FRAMING when {@link ArchitectState#getMode()} is
 * {@link de.mhus.vance.api.slartibartfast.ArchitectMode#EDIT} or
 * {@link de.mhus.vance.api.slartibartfast.ArchitectMode#UPDATE}.
 *
 * <p>Two branches, picked by mode:
 * <ul>
 *   <li>EDIT (recipes): loads the recipe identified by
 *       {@link ArchitectState#getTargetRecipeName()} from
 *       {@code recipes/_user/<name>.yaml}, YAML-parses it,
 *       detects {@link OutputSchemaType} from the {@code engine:}
 *       field, and stashes the raw yaml + parsed map on the
 *       state. The detected schema overrides any engine-param-
 *       supplied {@code outputSchemaType} — on EDIT the schema
 *       is a property of the existing recipe, not of the user
 *       request.</li>
 *   <li>UPDATE (scripts in v1; recipes later): loads the
 *       artefact at {@link ArchitectState#getExistingScriptRef()}
 *       verbatim (no parsing), stashes the body on
 *       {@link ArchitectState#setExistingScriptCode(String)}.
 *       The {@code outputSchemaType} stays whatever the caller
 *       declared — UPDATE is mode-orthogonal-to-schema.</li>
 * </ul>
 *
 * <p>Failure modes (all transition to FAILED with a clear
 * {@code failureReason}):
 * <ul>
 *   <li>EDIT: {@code targetRecipeName} is null/blank — FRAMING-LLM
 *       didn't extract a name despite EDIT mode</li>
 *   <li>UPDATE: {@code existingScriptRef} is null/blank — caller
 *       didn't supply the engine-param (should already be caught
 *       in {@code SlartibartfastEngine.validateModeInputs})</li>
 *   <li>document at the resolved path doesn't exist</li>
 *   <li>document is empty</li>
 *   <li>EDIT only: YAML parse fails or top-level isn't a map</li>
 *   <li>EDIT only: {@code engine:} field missing or names a
 *       schema Slart doesn't author (e.g. {@code engine: ford})</li>
 * </ul>
 *
 * <p>See {@code planning/slart-as-project-architect.md} §D-4 and
 * {@code planning/script-architect-executor-split.md} §5.1.
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

        if (state.getMode() == de.mhus.vance.api.slartibartfast.ArchitectMode.UPDATE) {
            executeUpdate(state, process);
            return;
        }
        executeEdit(state, process);
    }

    /**
     * UPDATE branch — loads a non-recipe artefact (SCRIPT_JS body
     * in v1) from {@link ArchitectState#getExistingScriptRef()}
     * verbatim. No YAML parsing, no schema-detection — the caller
     * already pinned the {@code outputSchemaType} and the
     * artefact body is opaque text to LOADING_EXISTING.
     */
    private void executeUpdate(
            ArchitectState state, ThinkProcessDocument process) {
        String path = state.getExistingScriptRef();
        if (path == null || path.isBlank()) {
            state.setFailureReason("LOADING_EXISTING entered in UPDATE "
                    + "mode without existingScriptRef — "
                    + "SlartibartfastEngine.validateModeInputs should "
                    + "have caught this");
            appendFailedIteration(state, "<missing ref>",
                    "FAILED — no existingScriptRef");
            return;
        }

        Optional<DocumentDocument> doc = documentService.findByPath(
                process.getTenantId(), process.getProjectId(), path);
        if (doc.isEmpty()) {
            state.setFailureReason("Existing artefact not found at "
                    + path + " — check that existingScriptRef is a "
                    + "valid document path in the current project.");
            appendFailedIteration(state, path,
                    "FAILED — document not found");
            return;
        }
        String content = documentService.readContent(doc.get());
        if (content == null || content.isBlank()) {
            state.setFailureReason("Existing artefact at " + path
                    + " is empty — nothing to update.");
            appendFailedIteration(state, path,
                    "FAILED — empty document");
            return;
        }

        state.setExistingScriptCode(content);
        log.info("Slartibartfast id='{}' LOADING_EXISTING (UPDATE) "
                        + "loaded {} chars from '{}'",
                process.getId(), content.length(), path);

        appendPassedIteration(state, path,
                "loaded " + content.length() + " chars (UPDATE)");
    }

    /**
     * EDIT branch — loads an existing user-namespace recipe,
     * YAML-parses it, and detects the schema from the
     * {@code engine:} field. Pinned schema goes to the state.
     */
    private void executeEdit(
            ArchitectState state, ThinkProcessDocument process) {
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

        String yaml = documentService.readContent(doc.get());
        if (yaml.isBlank()) {
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
