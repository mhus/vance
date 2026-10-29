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
 * {@code git_login}: makes the stored credential of an account on a host the one git uses from now on.
 * The token is never an argument and never part of the answer — the key is fixed by host and account
 * ({@link GitCredentialKey#storageKey()}), the tool only checks that it is there and readable. A new
 * token is stored under the same key; the next git call uses it, so logging in again is the
 * re-authentication.
 */
@Component
public class GitLoginTool implements Tool {

    private final GitCredentialStore store;
    private final GitCredentialTools support;

    GitLoginTool(GitCredentialStore store, GitCredentialTools support) {
        this.store = store;
        this.support = support;
    }

    @Override
    public String name() {
        return "git_login";
    }

    @Override
    public String description() {
        return "Check that git can sign in to <host> as <user>: the credential must already be stored under the"
                + " setting key credential.git.<user>@<host>. The token is never shown. Git then uses it by itself"
                + " for every repository on that host — you do not pass it anywhere. Call it again after storing a"
                + " new token to re-authenticate.";
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
        return "sign in to a git host with a stored token (github, gitlab)";
    }

    @Override
    public Set<String> labels() {
        return Set.of(ToolLabels.OPERATOR, "git", "credential");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return GitCredentialTools.SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String project = support.enforce(ctx, Action.READ);
        GitCredentialKey key = GitCredentialTools.key(params);
        Optional<GitCredentialStore.Located> located = store.locate(ctx.tenantId(), project, key);
        if (located.isEmpty()) {
            throw new ToolException("Key " + key.storageKey() + " not found. Store the token under exactly that key"
                    + " first (vault_secret_set with key=" + key.storageKey() + ", or ask the user to add it as a"
                    + " PASSWORD setting), then call git_login again.");
        }
        if (store.token(ctx.tenantId(), located.get()) == null) {
            throw new ToolException("Key " + key.storageKey() + " exists but cannot be read — store the token again.");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", key.storageKey());
        out.put("host", key.host());
        out.put("user", key.user());
        out.put("scope", located.get().tenantWide() ? "tenant" : "project");
        out.put("ready", true);
        return out;
    }
}
