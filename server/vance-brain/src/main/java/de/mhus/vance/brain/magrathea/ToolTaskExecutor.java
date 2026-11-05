package de.mhus.vance.brain.magrathea;

import de.mhus.vance.api.magrathea.MagratheaErrorKind;
import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.shared.magrathea.MagratheaStateSpec;
import de.mhus.vance.shared.permission.PermissionDeniedException;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tool-task executor (plan §4.3). Invokes a registered tool through
 * the existing {@link ToolDispatcher} surface — same lookup, same
 * permission gate the agent tools go through.
 *
 * <h3>YAML</h3>
 * <pre>
 * merge:
 *   type: tool_task
 *   tool: github.merge_pr
 *   params:
 *     url: "${params.pr_url}"
 *   storeAs: merge_result
 *   on: { success: done }
 *   catch:
 *     permission_error: escalate
 *     technical_error:  retry_or_abort
 * </pre>
 *
 * <h3>Outcome mapping</h3>
 * <ul>
 *   <li>Tool returns normally → {@code success}, output = the tool's result Map</li>
 *   <li>{@link PermissionDeniedException} → {@code permission_error}</li>
 *   <li>{@link ToolException} or other → sorted by {@link ToolFailure}: {@code rate_limited},
 *       {@code auth_error}, {@code not_found}, {@code unavailable}, else {@code technical_error}</li>
 *   <li>a REST result {@code {ok: false, status}} → the same sorting, the result kept as output</li>
 * </ul>
 *
 * <p>v1 forwards the {@code params:} block verbatim. The plan §7.1
 * tool-permission cascade (Tenant → Project → Workflow) is enforced
 * elsewhere by the dispatcher / permission service — this executor
 * doesn't second-guess it.
 */
@Component
@ConditionalOnProperty(value = "vance.services.magrathea", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class ToolTaskExecutor implements MagratheaTypeExecutor {

    private static final String SPEC_TOOL = "tool";
    private static final String SPEC_PARAMS = "params";

    private final ToolDispatcher toolDispatcher;
    private final ObjectMapper objectMapper;
    private final de.mhus.vance.shared.magrathea.MagratheaTaskService taskService;

    @Override
    public MagratheaTaskType type() {
        return MagratheaTaskType.TOOL_TASK;
    }

    @Override
    public Optional<TaskOutcome> execute(MagratheaTaskContext context) {
        MagratheaStateSpec state = context.state();
        String toolName = state.specString(SPEC_TOOL);
        if (toolName == null) {
            return Optional.of(
                    TaskOutcome.failure("tool_task '" + state.name() + "' is missing required 'tool:' field"));
        }
        Map<String, Object> toolParams = readParamsMap(state);

        ToolInvocationContext invocationCtx = new ToolInvocationContext(
                context.tenantId(),
                context.projectId(),
                /* sessionId */ null,
                /* processId */ null,
                context.startedBy());

        // A workflow tool_task runs the raw dispatcher without an engine
        // identity (no processId → no ContextToolsApi gate). Engine-role-
        // privileged tools (cross_process_create, user_*, tool_health_*,
        // tool_probe_*) are gated to specific engines; invoking them from a
        // generic workflow step would bypass that role gate (code-review
        // F1). None of them is a legitimate workflow tool, so refuse.
        Optional<ToolDispatcher.Resolved> resolvedTool = toolDispatcher.resolve(toolName, invocationCtx);
        if (resolvedTool.isPresent()
                && !resolvedTool.get().tool().requiresEngineRoles().isEmpty()) {
            log.warn(
                    "Magrathea tool_task '{}' refused engine-role-privileged tool " + "'{}' (requiresEngineRoles={})",
                    state.name(),
                    toolName,
                    resolvedTool.get().tool().requiresEngineRoles());
            return Optional.of(new TaskOutcome(
                    errorKindName(MagratheaErrorKind.PERMISSION_ERROR),
                    null,
                    "tool '" + toolName + "' is engine-role-privileged and cannot be "
                            + "invoked from a workflow tool_task",
                    null));
        }

        Map<String, Object> result;
        try {
            result = toolDispatcher.invoke(toolName, toolParams, invocationCtx);
        } catch (PermissionDeniedException ex) {
            log.warn(
                    "Magrathea tool_task '{}' permission denied for tool '{}': {}",
                    state.name(),
                    toolName,
                    ex.getMessage());
            return Optional.of(
                    new TaskOutcome(errorKindName(MagratheaErrorKind.PERMISSION_ERROR), null, ex.getMessage(), null));
        } catch (RuntimeException ex) {
            log.warn("Magrathea tool_task '{}' tool '{}' threw: {}", state.name(), toolName, ex.getMessage());
            return Optional.of(failed(context, ToolFailure.classify(ex), null));
        }

        // A REST call that came back as {ok: false, status: …} did not succeed: without
        // this a 429 would be a success to the workflow. The result stays as output, so
        // storeAs keeps the body the service sent.
        Optional<ToolFailure> httpFailure = ToolFailure.classifyResult(result);
        if (httpFailure.isPresent()) {
            log.warn(
                    "Magrathea tool_task '{}' tool '{}' failed: {}",
                    state.name(),
                    toolName,
                    httpFailure.get().message());
            return Optional.of(failed(context, httpFailure.get(), objectMapper.valueToTree(result)));
        }

        return Optional.of(TaskOutcome.successWith(result == null ? null : objectMapper.valueToTree(result)));
    }

    /**
     * The failed outcome, with the service's {@code Retry-After} remembered on the task so the
     * retry waits for it.
     */
    private TaskOutcome failed(MagratheaTaskContext context, ToolFailure failure, @Nullable JsonNode output) {
        if (failure.retryAfter() != null) {
            taskService.setRetryNotBefore(context.taskId(), failure.retryAfter());
        }
        return new TaskOutcome(errorKindName(failure.kind()), output, failure.message(), null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readParamsMap(MagratheaStateSpec state) {
        Object raw = state.specField(SPEC_PARAMS);
        if (raw == null) return Map.of();
        if (raw instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new IllegalArgumentException("tool_task '" + state.name() + "' params must be a map");
    }

    private static String errorKindName(MagratheaErrorKind kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }
}
