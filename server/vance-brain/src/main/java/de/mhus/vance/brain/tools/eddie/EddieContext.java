package de.mhus.vance.brain.tools.eddie;

import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.memory.MemoryDocument;
import de.mhus.vance.shared.memory.ScratchpadService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectKind;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Shared helper for Eddie hub-tools that operate on "the current
 * project". Eddie maintains an active project as a scratchpad slot
 * named {@value #ACTIVE_PROJECT_SLOT}; tools resolve the effective
 * project as
 *
 * <ol>
 *   <li>explicit {@code projectId} param (if provided), then</li>
 *   <li>the active project slot, then</li>
 *   <li>{@link ToolException} — caller forgot to pick one.</li>
 * </ol>
 *
 * <p>Validation enforces that the resolved project exists and is
 * non-{@link ProjectKind#SYSTEM} when {@code allowSystem == false} —
 * Eddie shouldn't accidentally write user-document operations into
 * its own hub project unless explicitly asked.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EddieContext {

    /**
     * Scratchpad slot under the Eddie hub-process holding the current
     * active project name (matches {@code ProjectDocument.name}).
     */
    public static final String ACTIVE_PROJECT_SLOT = "eddie.activeProject";

    private final ScratchpadService scratchpad;
    private final ProjectService projectService;
    private final ThinkProcessService thinkProcessService;

    /**
     * Reads the active-project slot for the calling Eddie process.
     * Returns empty if no project is currently switched-to.
     */
    public Optional<String> readActiveProject(ToolInvocationContext ctx) {
        String processId = ctx.processId();
        if (processId == null) {
            return Optional.empty();
        }
        return scratchpad.get(ctx.tenantId(), processId, ACTIVE_PROJECT_SLOT)
                .map(MemoryDocument::getContent)
                .filter(s -> s != null && !s.isBlank());
    }

    /** Writes the active-project slot. */
    public void writeActiveProject(ToolInvocationContext ctx, String projectName) {
        String processId = ctx.processId();
        if (processId == null) {
            throw new ToolException("Active-project switching requires a think-process scope");
        }
        scratchpad.set(
                ctx.tenantId(),
                ctx.projectId() == null ? "" : ctx.projectId(),
                ctx.sessionId(),
                processId,
                ACTIVE_PROJECT_SLOT,
                projectName);
    }

    /**
     * Resolves the project for a tool call. Order: explicit param →
     * active slot → ctx.projectId() (session's project) → fail.
     * Validates existence and SYSTEM-kind. Returns the
     * {@link ProjectDocument} so callers can pass {@code id},
     * {@code name}, etc. straight into downstream services.
     *
     * <p>The {@code ctx.projectId()} fallback covers Arthur/Ford
     * sessions that are bound to a single project: there is no need
     * for the LLM to call {@code project_switch} first. Eddie hub
     * sessions live in a SYSTEM project, so the fallback would
     * resolve {@code _user_<X>} — but the SYSTEM-rejection branch
     * below catches that and the user gets the right error.
     *
     * @param params       tool params, may carry a {@code projectId}
     *                     entry (string, project name)
     * @param ctx          tool invocation context
     * @param allowSystem  whether SYSTEM-kind projects are acceptable;
     *                     usually {@code false} for content tools
     */
    public ProjectDocument resolveProject(
            @Nullable Map<String, Object> params,
            ToolInvocationContext ctx,
            boolean allowSystem) {
        String explicit = paramString(params, "projectId");
        String name;
        if (isSubProcess(ctx)) {
            // Sub-processes (Marvin/Vogon/Ford workers spawned with a
            // parentProcessId) always run in the inherited project. The
            // worker LLM sometimes hallucinates a `projectId` from
            // training-data patterns (e.g. picking another project's
            // name it has seen elsewhere) — either as the tool param
            // or via project_switch into the active-slot. Forcing the
            // inherited context catches both paths.
            String inherited = ctx.projectId();
            if (inherited == null || inherited.isBlank()) {
                throw new ToolException(
                        "Sub-process invoked without an inherited "
                                + "projectId — engine spawn is broken");
            }
            if (explicit != null && !inherited.equals(explicit)) {
                log.warn("Sub-process tool ignored hallucinated projectId='{}' "
                                + "— forced to inherited project '{}' (process='{}')",
                        explicit, inherited, ctx.processId());
            } else {
                Optional<String> slot = readActiveProject(ctx);
                if (slot.isPresent() && !slot.get().equals(inherited)) {
                    log.warn("Sub-process tool ignored stale activeProject slot='{}' "
                                    + "— forced to inherited project '{}' (process='{}')",
                            slot.get(), inherited, ctx.processId());
                }
            }
            name = inherited;
        } else {
            name = explicit != null
                    ? explicit
                    : readActiveProject(ctx)
                            .or(() -> Optional.ofNullable(ctx.projectId())
                                    .filter(s -> !s.isBlank()))
                            .orElseThrow(() ->
                                    new ToolException(
                                            "No project specified and no active project set. "
                                                    + "Use project_switch(name) first, or pass "
                                                    + "the projectId parameter explicitly."));
        }
        ProjectDocument project = projectService.findByTenantAndName(ctx.tenantId(), name)
                .orElseThrow(() -> new ToolException(
                        "Project '" + name + "' not found in tenant '"
                                + ctx.tenantId() + "'"));
        if (!allowSystem && project.getKind() == ProjectKind.SYSTEM) {
            throw new ToolException(
                    "Project '" + name + "' is SYSTEM (hub project) — "
                            + "this operation requires a regular user project");
        }
        return project;
    }

    /**
     * A think-process is a "sub-process" when it was spawned by
     * another process (parentProcessId set). Marvin and Vogon
     * orchestrators spawn Ford workers this way; those workers
     * have no business switching projects mid-task.
     */
    public boolean isSubProcess(ToolInvocationContext ctx) {
        String pid = ctx.processId();
        if (pid == null || pid.isBlank()) return false;
        return thinkProcessService.findById(pid)
                .map(p -> p.getParentProcessId() != null
                        && !p.getParentProcessId().isBlank())
                .orElse(false);
    }

    private static @Nullable String paramString(@Nullable Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
