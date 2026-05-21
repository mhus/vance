package de.mhus.vance.brain.magrathea;

import de.mhus.vance.api.action.ScriptSource;
import de.mhus.vance.api.action.TriggerAction;
import de.mhus.vance.api.magrathea.MagratheaErrorKind;
import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.brain.action.ActionInvocation;
import de.mhus.vance.brain.action.ActionOutcome;
import de.mhus.vance.brain.action.ActionResult;
import de.mhus.vance.brain.action.ScriptActionExecutor;
import de.mhus.vance.brain.action.TriggerContext;
import de.mhus.vance.brain.action.TriggerKind;
import de.mhus.vance.shared.magrathea.MagratheaStateSpec;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * JavaScript task executor — runs a {@code script:} block from the
 * unified {@link TriggerAction.Script} surface inside a Magrathea workflow
 * step. Replaces the legacy shell-only {@code script_task} (now
 * {@code shell_task}, see
 * {@link de.mhus.vance.brain.magrathea.ShellTaskExecutor}).
 *
 * <h3>YAML</h3>
 * <pre>
 * classify:
 *   type: script_task
 *   source: document       # document | workspace
 *   path: scripts/classify.js
 *   dirName: scratch       # required when source=workspace
 *   timeoutSeconds: 30
 *   on: { success: route }
 *   catch: { business_error: human_review }
 * </pre>
 *
 * <h3>Outcome mapping</h3>
 * <p>Delegates outright to {@link ScriptActionExecutor}, which applies
 * the §5.3 permissive-structured mapping ({@link ScriptOutcomeMapper}).
 * The Magrathea outcome name is derived from
 * {@link ActionOutcome} by lower-casing the enum constant.
 *
 * <p>Workflow-scoped ⇒ {@link TriggerKind#WORKFLOW_TASK}: the
 * {@code VanceScriptApi} runs PROCESS_SCOPED with the full spawn-tool
 * surface. {@code planning/trigger-actions.md} §8.
 */
@Component
@ConditionalOnProperty(
        value = "vance.services.magrathea",
        havingValue = "true",
        matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class ScriptTaskExecutor implements MagratheaTypeExecutor {

    private static final String SPEC_SOURCE = "source";
    private static final String SPEC_PATH = "path";
    private static final String SPEC_DIRNAME = "dirName";
    private static final String SPEC_PARAMS = "params";

    private final ScriptActionExecutor scriptActionExecutor;
    private final ObjectMapper objectMapper;

    @Override
    public MagratheaTaskType type() {
        return MagratheaTaskType.SCRIPT_TASK;
    }

    @Override
    public Optional<TaskOutcome> execute(MagratheaTaskContext context) {
        MagratheaStateSpec state = context.state();
        TriggerAction.Script action;
        try {
            action = buildAction(state);
        } catch (IllegalStateException ex) {
            return Optional.of(TaskOutcome.failure(
                    "script_task '" + state.name() + "': " + ex.getMessage()));
        }
        TriggerContext triggerContext = new TriggerContext(
                context.tenantId(),
                context.projectId(),
                context.startedBy(),
                context.workflowRunId(),
                "workflow:" + context.workflowRunId() + ":" + state.name(),
                /*parentSessionId*/ null,
                /*parentProcessId*/ null);
        ActionResult result = scriptActionExecutor.execute(new ActionInvocation<>(
                action, triggerContext, TriggerKind.WORKFLOW_TASK));
        return Optional.of(mapOutcome(state, result));
    }

    private TriggerAction.Script buildAction(MagratheaStateSpec state) {
        String sourceRaw = state.specString(SPEC_SOURCE);
        if (sourceRaw == null) {
            throw new IllegalStateException("missing 'source' (document | workspace)");
        }
        ScriptSource source;
        try {
            source = ScriptSource.valueOf(sourceRaw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "unknown 'source' '" + sourceRaw + "' (expected: document | workspace)");
        }
        String path = state.specString(SPEC_PATH);
        if (path == null) {
            throw new IllegalStateException("missing 'path'");
        }
        String dirName = state.specString(SPEC_DIRNAME);
        if (source == ScriptSource.WORKSPACE && dirName == null) {
            throw new IllegalStateException(
                    "'dirName' is required when source=workspace");
        }
        if (source == ScriptSource.DOCUMENT && dirName != null) {
            throw new IllegalStateException(
                    "'dirName' must be omitted when source=document");
        }
        Integer timeoutSeconds = state.timeoutSeconds();
        Object rawParams = state.specField(SPEC_PARAMS);
        Map<String, Object> params = null;
        if (rawParams instanceof Map<?, ?> pm) {
            java.util.LinkedHashMap<String, Object> p = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> e : pm.entrySet()) {
                p.put(String.valueOf(e.getKey()), e.getValue());
            }
            params = p;
        }
        return new TriggerAction.Script(
                source, dirName, path, timeoutSeconds, params, null);
    }

    private TaskOutcome mapOutcome(MagratheaStateSpec state, ActionResult result) {
        Map<String, Object> output = result.output();
        tools.jackson.databind.JsonNode outputJson =
                output == null ? null : objectMapper.valueToTree(output);

        return switch (result.outcome()) {
            case SUCCESS -> TaskOutcome.successWith(outputJson);
            case BUSINESS_ERROR -> new TaskOutcome(
                    errorKindName(MagratheaErrorKind.BUSINESS_ERROR),
                    outputJson,
                    result.errorMessage(),
                    null);
            case TIMEOUT -> new TaskOutcome(
                    errorKindName(MagratheaErrorKind.TIMEOUT),
                    outputJson,
                    result.errorMessage() == null
                            ? "script_task '" + state.name() + "' timed out"
                            : result.errorMessage(),
                    null);
            case PERMISSION_ERROR -> new TaskOutcome(
                    errorKindName(MagratheaErrorKind.PERMISSION_ERROR),
                    outputJson,
                    result.errorMessage(),
                    null);
            case CANCELLED -> new TaskOutcome(
                    errorKindName(MagratheaErrorKind.TECHNICAL_ERROR),
                    outputJson,
                    result.errorMessage() == null ? "cancelled" : result.errorMessage(),
                    null);
            case TECHNICAL_ERROR, SCHEDULED -> new TaskOutcome(
                    errorKindName(MagratheaErrorKind.TECHNICAL_ERROR),
                    outputJson,
                    result.errorMessage() == null
                            ? "script_task '" + state.name() + "' failed"
                            : result.errorMessage(),
                    null);
        };
    }

    private static String errorKindName(MagratheaErrorKind kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }
}
