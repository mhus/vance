package de.mhus.vance.simpleauth.anus;

import de.mhus.vance.simpleauth.GrantRole;
import de.mhus.vance.simpleauth.GrantScopeType;
import de.mhus.vance.simpleauth.GrantSubjectType;
import de.mhus.vance.simpleauth.PermissionGrantDocument;
import de.mhus.vance.simpleauth.PermissionGrantService;
import java.util.List;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

/**
 * Operator CRUD over Simple-Auth grants — the anus counterpart of the Web-UI
 * grant area. Runs in the operator's cross-tenant god-mode (like the other anus
 * commands), so there is no per-scope authorization check; the tenant is always
 * an explicit argument. A TENANT-scope grant keys its {@code scopeId} on the
 * tenant itself.
 */
@Component
public class PermissionGrantCommands {

    private final PermissionGrantService grants;

    public PermissionGrantCommands(PermissionGrantService grants) {
        this.grants = grants;
    }

    @Command(name = {"permission", "grant", "list"}, description = "List grants on a scope (TENANT or PROJECT).")
    public String list(
            @Option(longName = "tenant", shortName = 'T', required = true) String tenant,
            @Option(longName = "scope-type", shortName = 's', defaultValue = "PROJECT") String scopeType,
            @Option(longName = "scope-id", shortName = 'p', defaultValue = "") String scopeId) {
        GrantScopeType st = scope(scopeType);
        String sid = scopeId(st, tenant, scopeId);
        List<PermissionGrantDocument> rows = grants.forScope(tenant, st, sid);
        if (rows.isEmpty()) {
            return "(no grants on " + st + ":" + sid + " in tenant '" + tenant + "')";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Grants on ").append(st).append(':').append(sid)
                .append(" in tenant '").append(tenant).append("':\n");
        for (PermissionGrantDocument g : rows) {
            sb.append("  ").append(g.getSubjectType()).append(':').append(g.getSubjectId())
                    .append(" -> ").append(g.getRole()).append('\n');
        }
        return sb.toString();
    }

    @Command(name = {"permission", "grant", "set"}, description = "Grant or update a role for a user or team on a scope.")
    public String set(
            @Option(longName = "tenant", shortName = 'T', required = true) String tenant,
            @Option(longName = "scope-type", shortName = 's', defaultValue = "PROJECT") String scopeType,
            @Option(longName = "scope-id", shortName = 'p', defaultValue = "") String scopeId,
            @Option(longName = "subject-type", shortName = 't', defaultValue = "USER") String subjectType,
            @Option(longName = "subject-id", shortName = 'n', required = true) String subjectId,
            @Option(longName = "role", shortName = 'r', required = true) String role) {
        GrantScopeType st = scope(scopeType);
        String sid = scopeId(st, tenant, scopeId);
        grants.set(tenant, st, sid, subject(subjectType), subjectId, role(role), "anus");
        return "Granted " + role.toUpperCase() + " to " + subjectType.toLowerCase()
                + " '" + subjectId + "' on " + st + ":" + sid + ".";
    }

    @Command(name = {"permission", "grant", "remove"}, description = "Remove a subject's grant on a scope.")
    public String remove(
            @Option(longName = "tenant", shortName = 'T', required = true) String tenant,
            @Option(longName = "scope-type", shortName = 's', defaultValue = "PROJECT") String scopeType,
            @Option(longName = "scope-id", shortName = 'p', defaultValue = "") String scopeId,
            @Option(longName = "subject-type", shortName = 't', defaultValue = "USER") String subjectType,
            @Option(longName = "subject-id", shortName = 'n', required = true) String subjectId) {
        GrantScopeType st = scope(scopeType);
        String sid = scopeId(st, tenant, scopeId);
        boolean removed = grants.remove(tenant, st, sid, subject(subjectType), subjectId);
        return removed
                ? "Removed grant for " + subjectType.toLowerCase() + " '" + subjectId + "' on " + st + ":" + sid + "."
                : "(no such grant)";
    }

    private static String scopeId(GrantScopeType scopeType, String tenant, String scopeId) {
        if (scopeType == GrantScopeType.TENANT) {
            return tenant;
        }
        if (scopeId == null || scopeId.isBlank()) {
            throw new IllegalArgumentException("--scope-id (project) is required for a PROJECT-scope grant");
        }
        return scopeId;
    }

    private static GrantScopeType scope(String v) {
        return GrantScopeType.valueOf(v.trim().toUpperCase());
    }

    private static GrantSubjectType subject(String v) {
        return GrantSubjectType.valueOf(v.trim().toUpperCase());
    }

    private static GrantRole role(String v) {
        return GrantRole.valueOf(v.trim().toUpperCase());
    }
}
