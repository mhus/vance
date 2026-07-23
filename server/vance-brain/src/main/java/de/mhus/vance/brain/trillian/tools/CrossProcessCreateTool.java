package de.mhus.vance.brain.trillian.tools;

import de.mhus.vance.api.action.TriggerAction;
import de.mhus.vance.brain.action.ActionExecutorRegistry;
import de.mhus.vance.brain.action.ActionResult;
import de.mhus.vance.brain.action.TriggerContext;
import de.mhus.vance.brain.action.TriggerKind;
import de.mhus.vance.brain.trillian.TrillianUserEngine;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectKind;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Cross-project worker spawn — Trillian-User's mechanism for getting
 * real work done in a project other than its home.
 *
 * <p>Mirrors {@link de.mhus.vance.brain.tools.process.ProcessCreateTool}
 * but takes an explicit {@code projectId} parameter. The spawned
 * worker process has {@code projectId = targetProject}; its
 * {@code ctx.projectId()} will therefore resolve to the target,
 * meaning {@code doc_*}, {@code file_*}, {@code exec_*}-tools the
 * worker invokes operate on the right project's scope by
 * construction — no project_switch / sudo / context-hacking needed.
 *
 * <p>Engine-role-gated on {@code trillian-user} so only Trillian's
 * user-loop sees it. The calling process must declare
 * {@code allowsCrossProjectSpawn=true} on its engine — for the
 * spawn-helper that's already true; for the Trillian-User-Engine
 * that's set in code.
 *
 * <p>Returns the standard spawn-metadata shape (processId, name,
 * engine, projectId) so the caller can hand the worker further input
 * via {@code process_steer} or watch it via {@code process_status}.
 *
 * <p>See {@code planning/trillian-engine.md} §9.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@de.mhus.vance.toolpack.SpawnTool
public class CrossProcessCreateTool implements Tool {

    private static final Map<String, Object> SCHEMA;
    static {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("projectId", Map.of(
                "type", "string",
                "description", "Name of the target project the worker should "
                        + "run in. Must exist in this tenant and not be a "
                        + "SYSTEM project. Use project_list to see what's "
                        + "available."));
        properties.put("name", Map.of(
                "type", "string",
                "description", "Stable process name. Must be unique within the "
                        + "calling session — pick a descriptive name like "
                        + "'count-docs-instant-hole'."));
        properties.put("recipe", Map.of(
                "type", "string",
                "description", "Recipe name (e.g. 'arthur', 'coding', "
                        + "'marvin'). Determines the engine + tool set the "
                        + "worker carries."));
        properties.put("goal", Map.of(
                "type", "string",
                "description", "One-line statement of what the worker should "
                        + "accomplish. Should be self-contained — the worker "
                        + "won't see your chat history."));
        properties.put("title", Map.of(
                "type", "string",
                "description", "Optional display title for the worker."));
        properties.put("steerContent", Map.of(
                "type", "string",
                "description", "Optional initial user-style message that the "
                        + "worker sees in its first turn — use this to expand "
                        + "the goal with constraints, context, or examples."));
        SCHEMA = Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("projectId", "name", "recipe", "goal"));
    }

    private final ActionExecutorRegistry actionRegistry;
    private final ProjectService projectService;
    private final ThinkProcessService thinkProcessService;
    private final de.mhus.vance.shared.permission.PermissionService permissionService;
    private final de.mhus.vance.brain.permission.SecurityContextFactory contextFactory;

    @Override
    public String name() {
        return "cross_process_create";
    }

    @Override
    public String description() {
        return "Spawn a worker process in a different project. The "
                + "worker's tool calls operate in that project's scope, "
                + "so its doc_* / file_* / exec_* tools act on the right "
                + "documents and workspace. The worker becomes a child "
                + "of you, so DONE / FAILED events flow back to your "
                + "inbox. Use this for any work that needs to happen "
                + "outside your home project.";
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Set<String> labels() {
        return Set.of("executive", "cross-project");
    }

    @Override
    public Set<String> requiresEngineRoles() {
        return Set.of(TrillianUserEngine.ROLE_TRILLIAN_USER);
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.processId() == null) {
            throw new ToolException("cross_process_create requires a process scope");
        }
        String projectId = stringOrThrow(params, "projectId");
        String name = stringOrThrow(params, "name");
        String recipeName = stringOrThrow(params, "recipe");
        String goal = stringOrThrow(params, "goal");
        String title = stringOrNull(params, "title");
        String steerContent = stringOrNull(params, "steerContent");
        // Default steerContent to goal — without an initial message the
        // spawned worker (e.g. Arthur) starts and immediately parks in
        // IDLE waiting for input it will never get. The goal text is
        // the natural opening message so the worker has something to
        // act on.
        if (steerContent == null) {
            steerContent = goal;
        }

        // 1. Validate the target project exists and is mortal.
        ProjectDocument project = projectService.findByTenantAndName(ctx.tenantId(), projectId)
                .orElseThrow(() -> new ToolException(
                        "Project '" + projectId + "' not found in tenant '"
                                + ctx.tenantId() + "'. Use project_list to see "
                                + "available projects."));
        if (project.getKind() == ProjectKind.SYSTEM) {
            throw new ToolException(
                    "Project '" + projectId + "' is a SYSTEM project — workers "
                            + "may not be spawned there. Pick a regular user "
                            + "project.");
        }

        // Cross-project spawn: the caller must be authorized to START work in
        // the *target* project. ToolDispatcher only checked the calling scope.
        permissionService.enforce(
                contextFactory.forToolSubject(ctx.tenantId(), ctx.userId()),
                new de.mhus.vance.shared.permission.Resource.Project(ctx.tenantId(), project.getName()),
                de.mhus.vance.shared.permission.Action.START);

        // 2. Dispatch via the standard action executor, but with the
        //    target projectId. The executor passes this through to
        //    ThinkProcessService.create, so the spawned process has
        //    projectId = target. allowsCrossProjectSpawn on the calling
        //    engine (Trillian-User) authorises the foreign projectId.
        TriggerAction.Recipe action = new TriggerAction.Recipe(
                recipeName,
                name,
                title,
                goal,
                /*inheritContextLevel*/ null,
                /*connectionProfile*/ null,
                steerContent,
                /*params*/ null,
                /*runAs*/ null);
        TriggerContext triggerCtx = TriggerContext.sessioned(
                ctx.tenantId(),
                project.getName(),
                /*resolvedRunAs*/ null,
                /*correlationId*/ null,
                /*sourceTag*/ "tool:cross_process_create",
                ctx.sessionId(),
                ctx.processId());

        ActionResult result = actionRegistry.execute(action, triggerCtx, TriggerKind.TOOL);

        switch (result.outcome()) {
            case SCHEDULED -> {
                ThinkProcessDocument spawned = thinkProcessService.findById(result.spawnedId())
                        .orElseThrow(() -> new ToolException(
                                "cross_process_create: spawned process '"
                                        + result.spawnedId() + "' is gone"));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("processId", spawned.getId());
                out.put("name", spawned.getName());
                out.put("engine", spawned.getThinkEngine());
                out.put("recipe", spawned.getRecipeName());
                out.put("projectId", spawned.getProjectId());
                // Explicit status hint so the LLM can't hallucinate a
                // result — the worker has not done anything yet. The
                // caller MUST end the current turn and wait for a
                // ProcessEvent before reporting back to Control.
                out.put("status", "spawned, awaiting completion");
                out.put("next",
                        "STOP this turn now. Do NOT call task_complete / task_failed / "
                                + "task_needs_input in the same turn — the worker has "
                                + "produced no result yet. You will wake on the worker's "
                                + "terminal ProcessEvent; report only then.");
                log.info("cross_process_create: tenant='{}' caller='{}' spawned='{}' targetProject='{}' recipe='{}'",
                        ctx.tenantId(), ctx.processId(), spawned.getId(),
                        project.getName(), recipeName);
                return out;
            }
            case SUCCESS -> {
                Map<String, Object> out = result.output();
                return out != null ? out : Map.of("status", "already_exists");
            }
            case TECHNICAL_ERROR, BUSINESS_ERROR, TIMEOUT, PERMISSION_ERROR, CANCELLED -> {
                String msg = result.errorMessage() == null
                        ? "cross_process_create failed" : result.errorMessage();
                throw new ToolException("cross_process_create: " + msg);
            }
        }
        throw new ToolException("cross_process_create: unexpected outcome " + result.outcome());
    }

    private static String stringOrThrow(@Nullable Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required and must be a non-empty string");
        }
        return s.trim();
    }

    private static @Nullable String stringOrNull(@Nullable Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        return raw instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
