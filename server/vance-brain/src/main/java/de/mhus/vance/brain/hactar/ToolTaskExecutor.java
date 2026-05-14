package de.mhus.vance.brain.hactar;

import de.mhus.vance.api.hactar.HactarErrorKind;
import de.mhus.vance.api.hactar.HactarTaskType;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.shared.hactar.HactarStateSpec;
import de.mhus.vance.shared.permission.PermissionDeniedException;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
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
 *   <li>{@link ToolException} or other → {@code technical_error}</li>
 * </ul>
 *
 * <p>v1 forwards the {@code params:} block verbatim. The plan §7.1
 * tool-permission cascade (Tenant → Project → Workflow) is enforced
 * elsewhere by the dispatcher / permission service — this executor
 * doesn't second-guess it.
 */
@Component
@ConditionalOnProperty(
        value = "vance.services.hactar",
        havingValue = "true",
        matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class ToolTaskExecutor implements HactarTypeExecutor {

    private static final String SPEC_TOOL   = "tool";
    private static final String SPEC_PARAMS = "params";

    private final ToolDispatcher toolDispatcher;
    private final ObjectMapper objectMapper;

    @Override
    public HactarTaskType type() {
        return HactarTaskType.TOOL_TASK;
    }

    @Override
    public Optional<TaskOutcome> execute(HactarTaskContext context) {
        HactarStateSpec state = context.state();
        String toolName = state.specString(SPEC_TOOL);
        if (toolName == null) {
            return Optional.of(TaskOutcome.failure(
                    "tool_task '" + state.name() + "' is missing required 'tool:' field"));
        }
        Map<String, Object> toolParams = readParamsMap(state);

        ToolInvocationContext invocationCtx = new ToolInvocationContext(
                context.tenantId(),
                context.projectId(),
                /* sessionId */ null,
                /* processId */ null,
                context.startedBy());

        Map<String, Object> result;
        try {
            result = toolDispatcher.invoke(toolName, toolParams, invocationCtx);
        } catch (PermissionDeniedException ex) {
            log.warn("Hactar tool_task '{}' permission denied for tool '{}': {}",
                    state.name(), toolName, ex.getMessage());
            return Optional.of(new TaskOutcome(
                    errorKindName(HactarErrorKind.PERMISSION_ERROR),
                    null,
                    ex.getMessage(),
                    null));
        } catch (ToolException ex) {
            log.warn("Hactar tool_task '{}' tool '{}' threw: {}",
                    state.name(), toolName, ex.getMessage());
            return Optional.of(new TaskOutcome(
                    errorKindName(HactarErrorKind.TECHNICAL_ERROR),
                    null,
                    ex.getMessage(),
                    null));
        } catch (RuntimeException ex) {
            log.warn("Hactar tool_task '{}' tool '{}' threw unexpected: {}",
                    state.name(), toolName, ex.getMessage());
            return Optional.of(new TaskOutcome(
                    errorKindName(HactarErrorKind.TECHNICAL_ERROR),
                    null,
                    ex.getMessage(),
                    null));
        }

        return Optional.of(TaskOutcome.successWith(
                result == null ? null : objectMapper.valueToTree(result)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readParamsMap(HactarStateSpec state) {
        Object raw = state.specField(SPEC_PARAMS);
        if (raw == null) return Map.of();
        if (raw instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new IllegalArgumentException(
                "tool_task '" + state.name() + "' params must be a map");
    }

    private static String errorKindName(HactarErrorKind kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }
}
