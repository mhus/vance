package de.mhus.vance.brain.vault;

import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.vault.VaultScope;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import org.springframework.stereotype.Component;

/**
 * Shared scope + authorization helper for the vault write tools
 * ({@code vault_secret_set} / {@code vault_secret_generate}).
 *
 * <p>Writing a secret is a project mutation, so it is gated with {@link Action#WRITE}
 * on the invocation's <em>local</em> project (never a project the LLM passes as a
 * param). The vault bound at that scope decides where the value actually lands; the
 * machine identity's own scope is the hard backstop (a read-only token fails the
 * downstream write regardless of this check).
 */
@Component
class VaultToolSupport {

    private final PermissionService permissionService;
    private final SecurityContextFactory contextFactory;

    VaultToolSupport(PermissionService permissionService, SecurityContextFactory contextFactory) {
        this.permissionService = permissionService;
        this.contextFactory = contextFactory;
    }

    /** Enforce project-scope WRITE and return the vault scope for the write. */
    VaultScope enforceAndScope(ToolInvocationContext ctx) {
        String project = requireProject(ctx);
        permissionService.enforce(
                contextFactory.forToolSubject(ctx.tenantId(), ctx.userId()),
                new Resource.Project(ctx.tenantId(), project),
                Action.WRITE);
        return new VaultScope(ctx.tenantId(), ctx.userId(), project);
    }

    /** The secret reference other tools/templates use to read the value back. */
    static String reference(String key) {
        return "vault:" + key;
    }

    private static String requireProject(ToolInvocationContext ctx) {
        String project = ctx.projectId();
        if (project == null || project.isBlank()) {
            throw new ToolException("vault secret write requires a project scope");
        }
        return project;
    }
}
