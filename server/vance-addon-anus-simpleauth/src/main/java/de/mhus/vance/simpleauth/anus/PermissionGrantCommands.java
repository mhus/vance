package de.mhus.vance.simpleauth.anus;

import de.mhus.vance.simpleauth.GrantRole;
import de.mhus.vance.simpleauth.GrantScopeType;
import de.mhus.vance.simpleauth.GrantSubjectType;
import de.mhus.vance.simpleauth.PermissionGrantDocument;
import de.mhus.vance.simpleauth.PermissionGrantService;
import java.util.List;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

/**
 * Operator CRUD over Simple-Auth grants — the anus counterpart of the Web-UI
 * grant area. Runs in the operator's cross-tenant god-mode (like the other anus
 * commands), so there is no per-scope authorization check; the tenant is always
 * an explicit argument. A TENANT-scope grant keys its {@code scopeId} on the
 * tenant itself.
 */
@ShellComponent
public class PermissionGrantCommands {

    private final PermissionGrantService grants;

    public PermissionGrantCommands(PermissionGrantService grants) {
        this.grants = grants;
    }

    @ShellMethod(key = "permission grant list", value = "List grants on a scope (TENANT or PROJECT).")
    public String list(
            @ShellOption(value = {"--tenant", "-T"}) String tenant,
            @ShellOption(value = {"--scope-type", "-s"}, defaultValue = "PROJECT") String scopeType,
            @ShellOption(value = {"--scope-id", "-p"}, defaultValue = "") String scopeId) {
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

    @ShellMethod(key = "permission grant set", value = "Grant or update a role for a user or team on a scope.")
    public String set(
            @ShellOption(value = {"--tenant", "-T"}) String tenant,
            @ShellOption(value = {"--scope-type", "-s"}, defaultValue = "PROJECT") String scopeType,
            @ShellOption(value = {"--scope-id", "-p"}, defaultValue = "") String scopeId,
            @ShellOption(value = {"--subject-type", "-t"}, defaultValue = "USER") String subjectType,
            @ShellOption(value = {"--subject-id", "-n"}) String subjectId,
            @ShellOption(value = {"--role", "-r"}) String role) {
        GrantScopeType st = scope(scopeType);
        String sid = scopeId(st, tenant, scopeId);
        grants.set(tenant, st, sid, subject(subjectType), subjectId, role(role), "anus");
        return "Granted " + role.toUpperCase() + " to " + subjectType.toLowerCase()
                + " '" + subjectId + "' on " + st + ":" + sid + ".";
    }

    @ShellMethod(key = "permission grant remove", value = "Remove a subject's grant on a scope.")
    public String remove(
            @ShellOption(value = {"--tenant", "-T"}) String tenant,
            @ShellOption(value = {"--scope-type", "-s"}, defaultValue = "PROJECT") String scopeType,
            @ShellOption(value = {"--scope-id", "-p"}, defaultValue = "") String scopeId,
            @ShellOption(value = {"--subject-type", "-t"}, defaultValue = "USER") String subjectType,
            @ShellOption(value = {"--subject-id", "-n"}) String subjectId) {
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
