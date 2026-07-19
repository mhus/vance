package de.mhus.vance.brain.damogran;

import de.mhus.vance.brain.damogran.DamogranManifest.TaskSpec;
import de.mhus.vance.brain.damogran.DamogranManifest.WorkspaceSpec;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.brain.tools.worktarget.BaseEngineTools;
import de.mhus.vance.brain.tools.worktarget.WorkTargetService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.worktarget.WorkTarget;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * {@code target: CLIENT} — run the compose against the connected Foot's
 * filesystem. Deliberately minimal (matches the use case: step through several
 * shell commands on the user's machine): <b>no managed workspace</b>, no
 * python/tex/js-workspace support. Only {@code exec} tasks, dispatched to
 * {@code client_exec_run} via the {@link de.mhus.vance.brain.tools.worktarget.WorkTargetDispatcher}
 * — the same routing the LLM {@code exec_run} tool uses when a process's
 * WorkTarget is CLIENT.
 *
 * <p>Requires a session-bound process with a <b>connected Foot</b>. It sets the
 * process WorkTarget to CLIENT and invokes {@code exec_run} through a fixed
 * {@link BaseEngineTools#WORK_TARGET} tool surface (independent of the bound
 * process's recipe — a CLIENT compose inherently needs exec/file).
 *
 * <p>v1 limits (fail fast): {@code import}/{@code export}/{@code delete} and
 * non-{@code exec} task types are not supported on CLIENT — Foot files are not
 * server-addressable, so there is no live output region either (a task's stdout
 * rides its result log). Bringing files onto / off the client is done inside the
 * shell commands themselves (curl, git, cat …).
 */
@Slf4j
@Component
public class ClientComposeRunner implements ComposeRunner {

    private static final String TARGET = "CLIENT";
    private static final int DEFAULT_DEADLINE_SECONDS = 120;

    private final WorkTargetService workTargetService;
    private final ThinkProcessService thinkProcessService;
    private final ToolDispatcher toolDispatcher;

    public ClientComposeRunner(WorkTargetService workTargetService,
                               ThinkProcessService thinkProcessService,
                               @Lazy ToolDispatcher toolDispatcher) {
        this.workTargetService = workTargetService;
        this.thinkProcessService = thinkProcessService;
        this.toolDispatcher = toolDispatcher;
    }

    @Override
    public String target() {
        return TARGET;
    }

    @Override
    public DamogranComposeResult run(
            String tenantId, String projectId, @Nullable String processId,
            DamogranManifest manifest, @Nullable String baseDir) {
        WorkspaceSpec ws = manifest.workspace();

        if (ws.delete() || ws.clear()) {
            throw new DamogranException(
                    "CLIENT target has no managed workspace — 'delete'/'clear' not supported");
        }
        if (!manifest.imports().isEmpty() || !manifest.exports().isEmpty()) {
            throw new DamogranException(
                    "CLIENT target does not support 'import'/'export' (v1) — move files with "
                            + "shell commands (curl/git/…) inside exec tasks");
        }
        if (processId == null) {
            throw new DamogranException(
                    "CLIENT target requires a session-bound process with a connected Foot");
        }

        ThinkProcessDocument process = thinkProcessService.findById(processId)
                .orElseThrow(() -> new DamogranException(
                        "CLIENT target: process not found: " + processId));
        String sessionId = process.getSessionId();
        if (!workTargetService.clientConnected(sessionId)) {
            throw new DamogranException(
                    "CLIENT target: no Foot client is bound to this session — connect the foot CLI");
        }

        workTargetService.set(processId, WorkTarget.client());
        ToolInvocationContext scope = new ToolInvocationContext(
                tenantId, projectId, sessionId, processId, null);
        ContextToolsApi tools = new ContextToolsApi(toolDispatcher, scope, BaseEngineTools.WORK_TARGET);

        List<DamogranTaskResult> results = new ArrayList<>();
        for (TaskSpec task : manifest.tasks()) {
            DamogranTaskResult result = runTask(tools, task);
            results.add(result);
            if (!result.isSuccess()) {
                log.debug("Damogran CLIENT compose '{}' halted at task '{}': {}",
                        ws.name(), task.type(), result.error());
                return new DamogranComposeResult(
                        DamogranStatus.FAILURE, ws.name(), List.copyOf(results), result.error());
            }
        }
        return new DamogranComposeResult(
                DamogranStatus.SUCCESS, ws.name(), List.copyOf(results), null);
    }

    private DamogranTaskResult runTask(ContextToolsApi tools, TaskSpec task) {
        if (!"exec".equals(task.type())) {
            return DamogranTaskResult.failure(
                    "task type '" + task.type() + "' is not supported on CLIENT target (only 'exec')");
        }
        Object command = task.params().get("command");
        if (command == null || command.toString().isBlank()) {
            return DamogranTaskResult.failure("exec task requires 'command'");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("command", command.toString());
        Object deadline = task.params().get("deadlineSeconds");
        params.put("deadlineSeconds", deadline instanceof Number n ? n.intValue() : DEFAULT_DEADLINE_SECONDS);

        Map<String, Object> out;
        try {
            out = tools.invoke("exec_run", params);
        } catch (RuntimeException e) {
            return DamogranTaskResult.failure("exec failed: " + e.getMessage());
        }
        Object exit = out.get("exitCode");
        String log = renderOutput(out);
        if (exit instanceof Number n && n.intValue() != 0) {
            return DamogranTaskResult.failure("exec exit code " + n.intValue(), log);
        }
        return DamogranTaskResult.success(List.of(), log);
    }

    private static String renderOutput(Map<String, Object> result) {
        Object out = result.get("output");
        if (out == null) {
            out = result.get("stdout");
        }
        return out != null ? String.valueOf(out) : String.valueOf(result);
    }
}
