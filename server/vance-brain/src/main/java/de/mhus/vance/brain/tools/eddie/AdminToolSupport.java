package de.mhus.vance.brain.tools.eddie;

import de.mhus.vance.api.roles.TenantRole;
import de.mhus.vance.brain.eddie.activity.EddieActivityService;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.activity.EddieActivityKind;
import de.mhus.vance.shared.activity.EntityRef;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * What the account, team and role tools share: the acting subject, the
 * fences every administering tool applies, the activity entry, and parameter
 * parsing.
 *
 * <p>The acting subject is always the invoking user. A call without one is
 * refused rather than run as {@link SecurityContext#SYSTEM} — that is what
 * {@link SecurityContextFactory#forToolSubject} would make of it, and SYSTEM
 * passes every permission check. A process pinned to its own project (a
 * worker, a confined engine) administers nothing.
 */
@Component
@RequiredArgsConstructor
public class AdminToolSupport {

    private final EddieContext eddieContext;
    private final EddieActivityService activityService;
    private final PermissionService permissionService;
    private final SecurityContextFactory contextFactory;

    /** The invoking user as permission subject; refuses headless calls and pinned processes. */
    public SecurityContext actor(ToolInvocationContext ctx, String toolName) {
        String userId = ctx.userId();
        if (userId == null || userId.isBlank()) {
            throw new ToolException(toolName + " requires a user identity");
        }
        if (eddieContext.isPinnedToOwnProject(ctx)) {
            throw new ToolException(toolName + " is not allowed in this process — its project is fixed "
                    + "(a sub-process worker, or an engine confined to its own project).");
        }
        return contextFactory.forToolSubject(ctx.tenantId(), userId);
    }

    public void enforce(SecurityContext actor, Resource resource, Action action) {
        permissionService.enforce(actor, resource, action);
    }

    public boolean check(SecurityContext actor, Resource resource, Action action) {
        return permissionService.check(actor, resource, action);
    }

    /** Effective tenant role of {@code username} — stored or from the external provider. */
    public TenantRole tenantRoleOf(String tenantId, String username) {
        return permissionService.tenantRoleOf(tenantId, username);
    }

    /** Appends an Eddie activity entry when the call comes from a process. */
    public void record(ToolInvocationContext ctx, EddieActivityKind kind, String summary, List<EntityRef> refs) {
        if (ctx.processId() == null || ctx.userId() == null) {
            return;
        }
        activityService.append(ctx.tenantId(), ctx.userId(), ctx.sessionId(), ctx.processId(), kind, summary, refs);
    }

    public static @Nullable String str(@Nullable Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (raw == null) {
            return null;
        }
        String s = raw.toString().trim();
        return s.isEmpty() ? null : s;
    }

    public static String req(@Nullable Map<String, Object> params, String key) {
        String s = str(params, key);
        if (s == null) {
            throw new ToolException("'" + key + "' is required and must be a non-empty string");
        }
        return s;
    }

    public static @Nullable Boolean bool(@Nullable Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (raw == null) {
            return null;
        }
        if (raw instanceof Boolean b) {
            return b;
        }
        String s = raw.toString().trim();
        if ("true".equalsIgnoreCase(s)) {
            return true;
        }
        if ("false".equalsIgnoreCase(s)) {
            return false;
        }
        throw new ToolException("'" + key + "' must be true or false");
    }

    /** A list of non-blank strings; a single string counts as a one-element list. */
    public static List<String> strings(@Nullable Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !o.toString().isBlank()) {
                    out.add(o.toString().trim());
                }
            }
            return out;
        }
        if (!raw.toString().isBlank()) {
            out.add(raw.toString().trim());
        }
        return out;
    }

    /** Refuses a {@code confirmName} that does not repeat {@code name}. */
    public static void requireConfirmed(String name, String confirmName, String what) {
        if (!name.equals(confirmName)) {
            throw new ToolException("confirmName '" + confirmName + "' does not match name '" + name + "' — the " + what
                    + " was not touched");
        }
    }
}
