package de.mhus.vance.brain.tools.runner;

import de.mhus.vance.shared.workspace.GitAuthProvider;
import de.mhus.vance.toolpack.ToolException;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.jgit.errors.UnsupportedCredentialItem;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.URIish;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Turns a {@code credentialAlias} into the token a runner needs for one clone. The alias
 * is resolved here, in the brain, through the same {@link GitAuthProvider} the server's own
 * git checkouts use; the runner never sees the alias store, only a username and token for
 * that one call.
 */
@Component
class GitCredentialResolver {

    private final GitAuthProvider authProvider;
    private final Set<String> credentialHosts;
    private final RunnerRepoUrl repoUrls;

    /**
     * @param credentialHosts {@code vance.runner.git-credential-hosts}: the hosts a credential may
     *     be sent to. The credential store does not say which host an alias belongs to, and the
     *     model picks both alias and URL, so an operator who wants a token pinned to its forge
     *     lists the forge here. Empty: any host that passes {@link RunnerRepoUrl}.
     */
    @Autowired
    GitCredentialResolver(
            GitAuthProvider authProvider,
            @Value("${vance.runner.git-credential-hosts:}") List<String> credentialHosts,
            RunnerRepoUrl repoUrls) {
        this.authProvider = authProvider;
        this.repoUrls = repoUrls;
        this.credentialHosts = credentialHosts.stream()
                .map(h -> h.strip().toLowerCase(Locale.ROOT))
                .filter(h -> !h.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * The {@code {username, token}} for {@code alias}, or {@code null} when none is asked for.
     *
     * @throws ToolException when an alias is named but the credential store does not know it
     */
    @Nullable
    Map<String, Object> resolve(
            @Nullable String tenantId, @Nullable String projectId, @Nullable String alias, String repoUrl) {
        if (alias == null || alias.isBlank()) {
            return null;
        }
        String host = repoUrls.check(repoUrl);
        boolean https = repoUrl.strip().toLowerCase(Locale.ROOT).startsWith("https://");
        if (!https && !host.isEmpty()) {
            throw new ToolException("A credential is only sent over https, not to " + repoUrl);
        }
        if (!host.isEmpty() && !credentialHosts.isEmpty() && !credentialHosts.contains(host)) {
            throw new ToolException("Git credentials may not be used for host '" + host + "' (allowed: "
                    + credentialHosts.stream().sorted().toList() + ")");
        }
        CredentialsProvider provider = authProvider.provide(tenantId, projectId, alias);
        if (provider == null) {
            throw new ToolException("Unknown git credential alias '" + alias + "'");
        }
        CredentialItem.Username user = new CredentialItem.Username();
        CredentialItem.Password password = new CredentialItem.Password();
        try {
            if (!provider.supports(user, password) || !provider.get(new URIish(repoUrl), user, password)) {
                throw new ToolException("Git credential alias '" + alias + "' has no username and token to offer");
            }
        } catch (UnsupportedCredentialItem | URISyntaxException e) {
            throw new ToolException("Git credential alias '" + alias + "' cannot be used: " + e.getMessage(), e);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("username", user.getValue());
        out.put("token", new String(password.getValue()));
        password.clear();
        return out;
    }
}
