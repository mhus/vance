package de.mhus.vance.brain.magrathea;

import de.mhus.vance.api.magrathea.MagratheaErrorKind;
import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.brain.tools.exec.ExecManager;
import de.mhus.vance.shared.magrathea.MagratheaStateSpec;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Shell-task executor (plan §4.2). Delegates to the existing
 * {@link ExecManager} via {@code submitTrackedAndRender} so workflow
 * shell commands flow through the same registry, log-files and
 * watchdog the agent {@code exec_run} tool uses — no parallel exec
 * surface.
 *
 * <p>Synchronous from the executor's point of view: it waits up to
 * {@code timeoutSeconds} (or 30s default) for the job to terminate and
 * maps the result map to a {@link TaskOutcome}.
 *
 * <h3>YAML</h3>
 * <pre>
 * run_checks:
 *   type: shell_task
 *   run: "npm test && npm run lint"
 *   dirName: build              # workspace RootDir name
 *   timeoutSeconds: 1800
 *   on: { success: review }
 *   catch: { business_error: debug }
 * </pre>
 *
 * <h3>Outcome mapping</h3>
 * <table>
 *   <tr><th>ExecJob status</th><th>exitCode</th><th>workflow outcome</th></tr>
 *   <tr><td>COMPLETED</td><td>0</td><td>{@code success}</td></tr>
 *   <tr><td>COMPLETED</td><td>!= 0</td><td>{@code business_error}</td></tr>
 *   <tr><td>KILLED</td><td>—</td><td>{@code timeout}</td></tr>
 *   <tr><td>FAILED / ORPHANED</td><td>—</td><td>{@code technical_error}</td></tr>
 *   <tr><td>RUNNING (waitMs exhausted)</td><td>—</td><td>{@code timeout}</td></tr>
 * </table>
 */
@Component
@ConditionalOnProperty(
        value = "vance.services.magrathea",
        havingValue = "true",
        matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class ShellTaskExecutor implements MagratheaTypeExecutor {

    private static final long DEFAULT_TIMEOUT_MS = 30_000L;
    private static final String SPEC_RUN     = "run";
    private static final String SPEC_DIRNAME = "dirName";

    private final ExecManager execManager;
    private final ObjectMapper objectMapper;

    @Override
    public MagratheaTaskType type() {
        return MagratheaTaskType.SHELL_TASK;
    }

    @Override
    public Optional<TaskOutcome> execute(MagratheaTaskContext context) {
        MagratheaStateSpec state = context.state();
        String command = state.specString(SPEC_RUN);
        if (command == null) {
            return Optional.of(TaskOutcome.failure(
                    "shell_task '" + state.name() + "' is missing required 'run:' field"));
        }
        String dirName = state.specString(SPEC_DIRNAME);
        long waitMs = state.timeoutSeconds() == null
                ? DEFAULT_TIMEOUT_MS
                : state.timeoutSeconds() * 1000L;

        Map<String, Object> result;
        try {
            result = execManager.submitTrackedAndRender(
                    context.tenantId(),
                    context.projectId(),
                    /* sessionId */ null,
                    /* processId */ null,
                    dirName,
                    command,
                    waitMs);
        } catch (RuntimeException ex) {
            // ExecException is package-private; the wider RuntimeException
            // catch covers it plus any infrastructure failure (e.g. workspace
            // dir missing). All map to technical_error.
            log.warn("Magrathea shell_task '{}' failed to submit: {}", state.name(), ex.getMessage());
            return Optional.of(new TaskOutcome(
                    errorKindName(MagratheaErrorKind.TECHNICAL_ERROR),
                    null,
                    ex.getMessage(),
                    null));
        }

        return Optional.of(mapOutcome(state, result));
    }

    private TaskOutcome mapOutcome(MagratheaStateSpec state, Map<String, Object> result) {
        String status = String.valueOf(result.get("status"));
        Integer exitCode = result.get("exitCode") instanceof Number n ? n.intValue() : null;
        String stderr = String.valueOf(result.getOrDefault("stderr", ""));

        // Build a compact output payload — the workflow's storeAs reads
        // from this, the LLM consumes it elsewhere. We keep stdout +
        // stderr + exitCode + durationMs.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status",   status);
        if (exitCode != null) payload.put("exitCode", exitCode);
        payload.put("stdout",   result.getOrDefault("stdout", ""));
        payload.put("stderr",   result.getOrDefault("stderr", ""));
        payload.put("durationMs", result.getOrDefault("durationMs", 0));
        payload.put("execJobId",  result.get("id"));

        switch (status) {
            case "COMPLETED":
                if (exitCode != null && exitCode == 0) {
                    return TaskOutcome.successWith(objectMapper.valueToTree(payload));
                }
                return new TaskOutcome(
                        errorKindName(MagratheaErrorKind.BUSINESS_ERROR),
                        objectMapper.valueToTree(payload),
                        "exit " + exitCode + (stderr.isBlank() ? "" : ": " + truncate(stderr, 240)),
                        null);
            case "KILLED":
                return new TaskOutcome(
                        errorKindName(MagratheaErrorKind.TIMEOUT),
                        objectMapper.valueToTree(payload),
                        "shell_task killed by watchdog",
                        null);
            case "FAILED":
            case "ORPHANED":
                return new TaskOutcome(
                        errorKindName(MagratheaErrorKind.TECHNICAL_ERROR),
                        objectMapper.valueToTree(payload),
                        "shell_task exec failed (" + status + ")",
                        null);
            case "RUNNING":
                // submitTrackedAndRender returned before the job
                // terminated — the workflow's task-level timeout
                // applies, treat as timeout outcome.
                return new TaskOutcome(
                        errorKindName(MagratheaErrorKind.TIMEOUT),
                        objectMapper.valueToTree(payload),
                        "shell_task wait exhausted (" + state.timeoutSeconds() + "s)",
                        null);
            default:
                return new TaskOutcome(
                        errorKindName(MagratheaErrorKind.TECHNICAL_ERROR),
                        objectMapper.valueToTree(payload),
                        "Unknown ExecJob status: " + status,
                        null);
        }
    }

    private static String errorKindName(MagratheaErrorKind kind) {
        return kind.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }
}
