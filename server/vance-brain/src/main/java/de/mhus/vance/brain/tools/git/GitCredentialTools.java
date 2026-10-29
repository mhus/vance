package de.mhus.vance.brain.tools.git;

import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.workspace.GitCredentialKey;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** What {@code git_login} and {@code git_logout} share: the parameters, the key, the project check. */
@Component
class GitCredentialTools {

    static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties",
                    Map.of(
                            "host", Map.of("type", "string", "description", "The forge's host name, e.g. github.com."),
                            "user", Map.of("type", "string", "description", "The account on that host.")),
            "required", List.of("host", "user"));

    private final PermissionService permissionService;
    private final SecurityContextFactory contextFactory;

    GitCredentialTools(PermissionService permissionService, SecurityContextFactory contextFactory) {
        this.permissionService = permissionService;
        this.contextFactory = contextFactory;
    }

    /** The key {@code params} name — host and account, nothing else is chosen by the caller. */
    static GitCredentialKey key(Map<String, Object> params) {
        String host = text(params, "host");
        String user = text(params, "user");
        return new GitCredentialKey(user, host);
    }

    /** The invocation's own project, after checking the caller may {@code action} on it. */
    String enforce(ToolInvocationContext ctx, Action action) {
        String project = ctx.projectId();
        if (project == null || project.isBlank()) {
            throw new ToolException("git credentials need a project scope");
        }
        permissionService.enforce(
                contextFactory.forToolSubject(ctx.tenantId(), ctx.userId()),
                new Resource.Project(ctx.tenantId(), project),
                action);
        return project;
    }

    private static String text(Map<String, Object> params, String name) {
        Object raw = params == null ? null : params.get(name);
        if (raw instanceof String s && !s.isBlank()) {
            return s.strip();
        }
        throw new ToolException("Missing required parameter '" + name + "'");
    }
}
