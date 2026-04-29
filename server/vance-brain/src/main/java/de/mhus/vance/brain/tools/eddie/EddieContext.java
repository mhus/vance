package de.mhus.vance.brain.tools.eddie;

import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.memory.MemoryDocument;
import de.mhus.vance.shared.memory.ScratchpadService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectKind;
import de.mhus.vance.shared.project.ProjectService;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
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
public class EddieContext {

    /**
     * Scratchpad slot under the Eddie hub-process holding the current
     * active project name (matches {@code ProjectDocument.name}).
     */
    public static final String ACTIVE_PROJECT_SLOT = "eddie.activeProject";

    private final ScratchpadService scratchpad;
    private final ProjectService projectService;

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
     * active slot → fail. Validates existence and SYSTEM-kind. Returns
     * the {@link ProjectDocument} so callers can pass {@code id},
     * {@code name}, etc. straight into downstream services.
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
        String name = explicit != null
                ? explicit
                : readActiveProject(ctx).orElseThrow(() ->
                        new ToolException(
                                "No project specified and no active project set. "
                                        + "Use project_switch(name) first, or pass "
                                        + "the projectId parameter explicitly."));
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

    private static @Nullable String paramString(@Nullable Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
