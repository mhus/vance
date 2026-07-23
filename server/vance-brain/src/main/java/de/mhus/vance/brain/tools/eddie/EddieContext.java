package de.mhus.vance.brain.tools.eddie;

import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.shared.home.HomeBootstrapService;
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
 * project". Eddie maintains an active foreign project (her "spot") as
 * the typed {@code workingProjectId} field on her own
 * {@link de.mhus.vance.shared.thinkprocess.ThinkProcessDocument}. Tools
 * resolve the effective project as
 *
 * <ol>
 *   <li>{@link ToolInvocationContext#workingProjectId() ctx.workingProjectId()}
 *       — the spot (carried into the tool context by the brain at
 *       dispatch time), or</li>
 *   <li>explicit {@code projectId} param when the LLM addresses
 *       something <em>other</em> than the current spot (the rare
 *       cross-project case — kit lookups, project_list etc.), or</li>
 *   <li>{@link ToolException} — caller forgot to pick one.</li>
 * </ol>
 *
 * <p>The explicit param is intentionally <em>second</em> for Eddie's
 * home/spot model: the trust-boundary lives on the context, not on
 * LLM-provided params. Sub-processes (Marvin/Vogon/Ford workers) ignore
 * both inputs and always operate on the inherited
 * {@link ToolInvocationContext#projectId()} — their LLM cannot
 * hallucinate cross-project access.
 *
 * <p>Validation enforces that the resolved project exists and is
 * non-{@link ProjectKind#SYSTEM} when {@code allowSystem == false},
 * <i>except</i> for the caller's own user-hub project
 * ({@code _user_<userId>}) — Eddie's chat lives there and the user
 * expects "save as document" to work in-place without first
 * delegating to a fresh user project. Other SYSTEM projects
 * (tenant-wide {@code _vance}, other users' hubs) remain protected.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EddieContext {

    private final ProjectService projectService;
    private final ThinkProcessService thinkProcessService;
    private final de.mhus.vance.shared.permission.PermissionService permissionService;
    private final de.mhus.vance.brain.permission.SecurityContextFactory contextFactory;

    /**
     * Reads the active "spot" project for the calling Eddie process —
     * the foreign project currently coordinated. Backed by
     * {@code ThinkProcessDocument.workingProjectId}; returns empty when
     * no spot is set or the call lacks a process scope.
     *
     * <p>Prefers the live {@link ToolInvocationContext#workingProjectId()}
     * (which the dispatcher fills from the process record) and falls
     * back to a fresh Mongo read only for legacy contexts that didn't
     * thread the field through.
     */
    public Optional<String> readActiveProject(ToolInvocationContext ctx) {
        String fromCtx = ctx.workingProjectId();
        if (fromCtx != null && !fromCtx.isBlank()) {
            return Optional.of(fromCtx);
        }
        String processId = ctx.processId();
        if (processId == null || processId.isBlank()) {
            return Optional.empty();
        }
        return thinkProcessService.findById(processId)
                .map(p -> p.getWorkingProjectId())
                .filter(s -> s != null && !s.isBlank());
    }

    /**
     * Sets the active "spot" project. Atomic — writes
     * {@code ThinkProcessDocument.workingProjectId} on the calling
     * Eddie process via the typed service helper. Throws when invoked
     * outside a think-process scope.
     */
    public void writeActiveProject(ToolInvocationContext ctx, String projectName) {
        String processId = ctx.processId();
        if (processId == null || processId.isBlank()) {
            throw new ToolException("Active-project switching requires a think-process scope");
        }
        thinkProcessService.setWorkingProjectId(processId, projectName);
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
        if (!allowSystem && project.getKind() == ProjectKind.SYSTEM
                && !isCallerOwnHub(project, ctx)) {
            throw new ToolException(
                    "Project '" + name + "' is SYSTEM (hub project) — "
                            + "this operation requires a regular user project");
        }
        // Hard READ check at the resolution source: the projectId param is
        // caller-controllable, and ToolDispatcher only checks the caller's
        // own scope — so a tool could otherwise target ANY project in the
        // tenant. Every read/list/write tool resolves through here, so
        // gating READ once here covers them all (write tools additionally
        // enforce WRITE downstream). forToolSubject maps a null userId →
        // SYSTEM (headless/internal passes); the resolver decides (R3/R7).
        // (permission-system finding #9/#11 — read path)
        permissionService.enforce(
                contextFactory.forToolSubject(ctx.tenantId(), ctx.userId()),
                new de.mhus.vance.shared.permission.Resource.Project(
                        ctx.tenantId(), project.getName()),
                de.mhus.vance.shared.permission.Action.READ);
        return project;
    }

    /**
     * Carve-out for the SYSTEM-kind gate: the caller's own
     * {@code _user_<userId>} hub project IS a SYSTEM project but is
     * also the caller's working space. Allow content tools to operate
     * there. Other SYSTEM projects (tenant {@code _vance}, other
     * users' hubs) stay blocked.
     */
    private static boolean isCallerOwnHub(ProjectDocument project, ToolInvocationContext ctx) {
        String userId = ctx.userId();
        if (userId == null || userId.isBlank()) return false;
        return HomeBootstrapService.hubProjectName(userId).equals(project.getName());
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
