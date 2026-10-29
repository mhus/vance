package de.mhus.vance.brain.tools.runner;

import de.mhus.vance.shared.workspace.GitAuthProvider;
import de.mhus.vance.shared.workspace.GitCredentialException;
import de.mhus.vance.shared.workspace.GitCredentialKey;
import de.mhus.vance.toolpack.ToolException;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.eclipse.jgit.errors.UnsupportedCredentialItem;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.URIish;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The token a runner needs for one clone. Nobody names a credential: the repository's host (and
 * the account, when the URL names one) selects it from the same {@link GitAuthProvider} the
 * server's own git checkouts use. No stored credential means the repository is public. The runner
 * never sees the credential store, only a username and token for that one call.
 */
@Component
class GitCredentialResolver {

    private final GitAuthProvider authProvider;
    private final RunnerRepoUrl repoUrls;

    GitCredentialResolver(GitAuthProvider authProvider, RunnerRepoUrl repoUrls) {
        this.authProvider = authProvider;
        this.repoUrls = repoUrls;
    }

    /**
     * The {@code {username, token}} to clone {@code repoUrl} with, or {@code null} for anonymous
     * access: {@code publicAccess} was asked for, the source is local, or the host has no stored
     * credential.
     *
     * @throws ToolException when the URL is refused, several accounts match, or the credential
     *     would have to travel over plain http
     */
    @Nullable
    Map<String, Object> resolve(
            @Nullable String tenantId, @Nullable String projectId, String repoUrl, boolean publicAccess) {
        String host = repoUrls.check(repoUrl);
        if (publicAccess || host.isEmpty()) {
            return null;
        }
        CredentialsProvider provider;
        try {
            provider = authProvider.provide(tenantId, projectId, host, GitCredentialKey.userOf(repoUrl));
        } catch (GitCredentialException e) {
            throw new ToolException(e.getMessage(), e);
        }
        if (provider == null) {
            return null;
        }
        if (!repoUrl.strip().toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw new ToolException("Stored credentials for " + host + " are only sent over https, not to " + repoUrl
                    + " — use an https URL, or set public to go without them");
        }
        CredentialItem.Username user = new CredentialItem.Username();
        CredentialItem.Password password = new CredentialItem.Password();
        try {
            if (!provider.supports(user, password) || !provider.get(new URIish(repoUrl), user, password)) {
                throw new ToolException(
                        "The stored git credential for " + host + " has no username and token to offer");
            }
        } catch (UnsupportedCredentialItem | URISyntaxException e) {
            throw new ToolException("The stored git credential for " + host + " cannot be used: " + e.getMessage(), e);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("username", user.getValue());
        out.put("token", new String(password.getValue()));
        password.clear();
        return out;
    }
}
