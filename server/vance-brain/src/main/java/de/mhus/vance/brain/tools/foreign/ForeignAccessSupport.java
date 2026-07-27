package de.mhus.vance.brain.tools.foreign;

import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.WriteActor;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectKind;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Shared helper for the {@code foreign_*} cross-project tools. Centralises
 * the one thing every foreign tool must do identically: resolve an
 * explicitly-named project in the caller's tenant and gate access on it.
 *
 * <p>Deliberately <em>not</em> {@code EddieContext.resolveProject}: that
 * resolver falls back to the session/spot project and — critically —
 * suppresses the explicit {@code projectId} for sub-processes (an
 * anti-hallucination guard for workers). Foreign access is the deliberate
 * intent, so the explicit id must win; the guard against reaching the wrong
 * project is the {@link PermissionService} check here, not the resolver.
 */
@Component
@RequiredArgsConstructor
public class ForeignAccessSupport {

    private final ProjectService projectService;
    private final DocumentService documentService;
    private final PermissionService permissionService;
    private final SecurityContextFactory contextFactory;

    /**
     * Resolve an explicitly-named project in the caller's tenant and enforce
     * {@code action} on it. Rejects a blank name, an unknown project, and any
     * SYSTEM project ({@code _vance}, {@code _tenant}, {@code _user_*}) — the
     * foreign tools work on regular projects only; system namespaces are
     * reachable through the normal document cascade, not here.
     */
    public ProjectDocument resolveForeign(String projectId, ToolInvocationContext ctx, Action action) {
        if (projectId == null || projectId.isBlank()) {
            throw new ToolException("Missing required parameter 'projectId'");
        }
        ProjectDocument project = projectService.findByTenantAndName(ctx.tenantId(), projectId)
                .orElseThrow(() -> new ToolException(
                        "Project '" + projectId + "' not found in tenant '" + ctx.tenantId() + "'"));
        if (project.getKind() == ProjectKind.SYSTEM) {
            throw new ToolException("Project '" + projectId + "' is a SYSTEM project; "
                    + "foreign_* tools operate on regular projects only");
        }
        permissionService.enforce(
                contextFactory.forToolSubject(ctx.tenantId(), ctx.userId()),
                new Resource.Project(ctx.tenantId(), project.getName()),
                action);
        return project;
    }

    /**
     * Resolve the copy/move <em>destination</em> project: an explicit
     * {@code toProjectId} when given, otherwise the caller's current project
     * ({@code ctx.projectId()}). Rejects an unknown project and any SYSTEM
     * project except the tenant {@code _vance} staging area — the same guard
     * the write-into-project path has always used. Authorization of the actual
     * write happens separately via {@link #enforceDoc} (CREATE on the target
     * document), so a READER-only caller cannot copy out of a foreign project
     * into somewhere they can't write.
     */
    public ProjectDocument resolveTarget(@Nullable String toProjectId, ToolInvocationContext ctx) {
        String name = (toProjectId != null && !toProjectId.isBlank())
                ? toProjectId.trim() : ctx.projectId();
        if (name == null || name.isBlank()) {
            throw new ToolException("No target project: not running inside a project scope — "
                    + "pass toProjectId explicitly");
        }
        ProjectDocument target = projectService.findByTenantAndName(ctx.tenantId(), name)
                .orElseThrow(() -> new ToolException(
                        "Target project '" + name + "' not found in tenant '" + ctx.tenantId() + "'"));
        if (target.getKind() == ProjectKind.SYSTEM
                && !name.equals(ProjectService.SYSTEM_NAME_PREFIX + "vance")) {
            throw new ToolException("Cannot copy/move into SYSTEM project '" + name + "'");
        }
        return target;
    }

    /** Document-level authorization for a mutation target (copy/move destination). */
    public void enforceDoc(ToolInvocationContext ctx, String projectName, String path, Action action) {
        permissionService.enforce(
                contextFactory.forToolSubject(ctx.tenantId(), ctx.userId()),
                new Resource.Document(ctx.tenantId(), projectName, path),
                action);
    }

    /** The write actor for a tool-driven DocumentService write (audit-carrying, reason by path). */
    public WriteActor writeActor(ToolInvocationContext ctx, String path) {
        return contextFactory.writeActor(ctx.tenantId(), ctx.userId(), path);
    }

    /** All projects the caller may READ — the authoritative visibility gate ({@code listReadableBy}). */
    public List<ProjectDocument> listReadable(ToolInvocationContext ctx) {
        return projectService.listReadableBy(
                ctx.tenantId(), contextFactory.forToolSubject(ctx.tenantId(), ctx.userId()));
    }

    public DocumentService documents() {
        return documentService;
    }

    public ProjectService projects() {
        return projectService;
    }

    /**
     * True for reserved / system document paths that must never cross the
     * project boundary through {@code foreign_*} (recipes, settings, manuals,
     * schedulers, trash — everything under a {@code _}-prefixed namespace).
     * Filtered from foreign listings/searches and rejected for foreign read/copy.
     */
    public static boolean reserved(String path) {
        return path != null && path.startsWith("_");
    }

    /**
     * Read a document's text content, buffer-agnostic — a foreign document is
     * not in this process's write-behind buffer, so disk is authoritative.
     */
    public String readText(DocumentDocument doc) {
        String inline = documentService.readContent(doc);
        if (inline != null) {
            return inline;
        }
        try (InputStream in = documentService.loadContent(doc)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException("Failed to read document content: " + e.getMessage(), e);
        }
    }
}
