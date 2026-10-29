package de.mhus.vance.brain.tools.git;

import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.workspace.GitCredentialKey;
import de.mhus.vance.shared.workspace.GitCredentialStore;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * {@code git_logout}: revokes the project's stored credential of an account on a host. Git then goes to
 * that host anonymously again (or with the tenant-wide credential, which only an administrator removes).
 */
@Component
public class GitLogoutTool implements Tool {

    private final GitCredentialStore store;
    private final GitCredentialTools support;

    GitLogoutTool(GitCredentialStore store, GitCredentialTools support) {
        this.store = store;
        this.support = support;
    }

    @Override
    public String name() {
        return "git_logout";
    }

    @Override
    public String description() {
        return "Revoke the stored git credential of <user> on <host> for this project (setting key"
                + " credential.git.<user>@<host>). Git goes to that host anonymously afterwards.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public boolean deferred() {
        return true;
    }

    @Override
    public String searchHint() {
        return "revoke / remove a stored git token (sign out of github, gitlab)";
    }

    @Override
    public Set<String> labels() {
        return Set.of(ToolLabels.OPERATOR, "git", "credential", "write");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return GitCredentialTools.SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String project = support.enforce(ctx, Action.WRITE);
        GitCredentialKey key = GitCredentialTools.key(params);
        Optional<GitCredentialStore.Located> located = store.locate(ctx.tenantId(), project, key);
        if (located.isEmpty()) {
            throw new ToolException("Key " + key.storageKey() + " not found — nothing to revoke.");
        }
        if (located.get().tenantWide() || !store.revoke(ctx.tenantId(), project, key)) {
            throw new ToolException("Key " + key.storageKey() + " is the tenant-wide credential; only an"
                    + " administrator removes it in the settings.");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", key.storageKey());
        out.put("revoked", true);
        return out;
    }
}
